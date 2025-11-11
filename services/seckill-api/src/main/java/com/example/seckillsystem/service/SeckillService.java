package com.example.seckillsystem.service;

import com.example.seckillsystem.model.Product;
import com.example.seckillsystem.repository.ProductRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SeckillService (Outbox pattern flow)
 *
 * 主要变更：
 *  - 修复 Lua keys 构造：每次传入具体桶 key 与 user set key
 *  - submitSeckillOrder 采用 "Lua pre-decrement -> createOrderAndOutboxTx" 流程
 *  - 若 DB 写失败，会调用 compensateRedisIncrease 做补偿
 *
 * 注意：
 *  - OrderService.createOrderAndOutboxTx(...) 应在单个 DB 事务内写 orders + outbox
 *  - Redis Lua 脚本需要保证幂等性（脚本返回值语义同项目约定）
 */
@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    // --- V1.3 依赖 ---
    @Autowired
    private ExecutorService seckillExecutorService; // 可用于异步检查/加载缓存等

    // --- Redis / Lua ---
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Resource(name = "seckillScriptV5")
    private DefaultRedisScript<Long> seckillScript;

    // --- persistence / cache / locking ---
    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired @Lazy
    private SeckillService self; // for @Cacheable self-invocation

    // --- Outbox integration: delegate to OrderService to create order + outbox in one tx ---
    @Autowired
    private OrderService orderService;

    // --- constants ---
    public static final String STOCK_KEY_PREFIX = "seckill:stock:";
    public static final String USER_SET_KEY_PREFIX = "seckill:users:";
    public static final String LOCK_KEY_PREFIX = "lock:product:";
    public static final int BUCKET_COUNT = 10;
    public static final String TOTAL_STOCK_KEY_SUFFIX = ":total";
    public static final String BUCKET_KEY_SUFFIX = ":bucket_";
    public static final int BUCKET_RETRY_COUNT = 3;

    private static final Random random = new Random();

    /**
     * API 入口：提交秒杀下单请求（Outbox 路径）
     *
     * 流程：
     * 1) ensureStockCacheIsReady(...)
     * 2) executeLuaScript(...) 做 Redis 预扣（fast-path）
     * 3) 若 Lua 返回 success -> 生成 requestId 调用 OrderService.createOrderAndOutboxTx(...)
     * 4) 若 DB 写失败 -> 调用 compensateRedisIncrease(...) 异步/同步补偿 Redis
     *
     * 返回值为用户友好提示（演示/面试用）
     */
    public String submitSeckillOrder(Long productId, Long userId) {
        String threadName = Thread.currentThread().getName();

        // 1. 缓存准备
        try {
            boolean cacheReady = ensureStockCacheIsReady(productId, threadName);
            if (!cacheReady) {
                log.warn("[{}] 商品 {} 缓存未就绪，拒绝请求。", threadName, productId);
                return "商品信息加载中，请稍后再试";
            }
        } catch (Exception e) {
            log.error("[{}] 缓存检查异常", threadName, e);
            return "系统繁忙，请稍后";
        }

        // 2. 选择桶（可用 deterministic 或随机策略）
        int bucketIndex = chooseBucket(productId, userId);

        // 3. 执行 Lua 预扣（local fast-path）
        List<String> keys = Arrays.asList(
                STOCK_KEY_PREFIX + productId + BUCKET_KEY_SUFFIX + bucketIndex,
                USER_SET_KEY_PREFIX + productId
        );

        Long luaResult;
        try {
            luaResult = redisTemplate.execute(
                    seckillScript,
                    keys,
                    String.valueOf(userId),
                    String.valueOf(productId),
                    String.valueOf(bucketIndex)
            );
        } catch (Exception e) {
            log.error("[{}] Lua 脚本执行异常: productId={}, bucket={}", threadName, productId, bucketIndex, e);
            return "系统繁忙，请稍后";
        }

        if (luaResult == null) {
            log.error("[{}] Lua 返回 null，可能执行异常", threadName);
            return "系统繁忙，请稍后";
        }

        // 解析 Lua 返回值：0 = success, 1 = duplicate, 2 = sold out, 3 = bucket empty (try other buckets)
        if (luaResult == 0L) {
            // 4. Lua 成功 -> 在同一 DB 事务里写 orders + outbox（由 OrderService 执行）
            String requestId = UUID.randomUUID().toString(); // 幂等 id（也可由客户端传入）
            try {
                String orderId = orderService.createOrderAndOutboxTx(userId, productId, requestId);
                log.info("[{}] Lua 预扣成功，已写入 orders+outbox: orderId={}, requestId={}", threadName, orderId, requestId);
                // 返回短响应，实际最终状态以异步通知或查询为准
                return "秒杀已进入队列，orderId=" + orderId;
            } catch (Exception ex) {
                log.error("[{}] DB 写入失败，触发 Redis 补偿: productId={}, bucket={}, userId={}", threadName, productId, bucketIndex, userId, ex);
                // DB 写失败 -> 必须补偿 Redis（增加回扣并移除 user 标记）
                try {
                    compensateRedisIncrease(productId, bucketIndex, userId);
                } catch (Exception compEx) {
                    // 补偿失败需要记录并报警（此处简化为日志）
                    log.error("[{}] 补偿 Redis 失败，需人工/自动化重试或对账: productId={}, bucket={}, userId={}", threadName, productId, bucketIndex, userId, compEx);
                }
                return "系统繁忙，请稍后重试";
            }
        } else {
            // 非成功情况，返回友好提示
            return luaFailureMessage(luaResult);
        }
    }

    /**
     * 执行 Lua 脚本（暴露给可能的事务监听/测试）—— 但注意：不应把对 Redis 的变更放到 DB 事务回滚的期望里。
     * 该方法用于单次尝试并返回语义化结果（0 success, 1 duplicate, 2 sold out, 3 bucket empty）
     */
    public Long executeLuaScript(Long productId, Long userId, String threadName) {
        log.info("[{}] 执行 Lua 脚本（外部调用） productId={}, userId={}", threadName, productId, userId);

        Long result = -1L;
        for (int i = 0; i < BUCKET_RETRY_COUNT; i++) {
            int bucketIndex = random.nextInt(BUCKET_COUNT) + 1;

            List<String> keys = Arrays.asList(
                    STOCK_KEY_PREFIX + productId + BUCKET_KEY_SUFFIX + bucketIndex,
                    USER_SET_KEY_PREFIX + productId
            );

            result = redisTemplate.execute(
                    seckillScript,
                    keys,
                    String.valueOf(userId),
                    String.valueOf(productId),
                    String.valueOf(bucketIndex)
            );

            if (result == null) {
                log.error("[{}] Lua 返回 null（异常），中止重试", threadName);
                break;
            }

            if (result == 0L) {
                log.info("[{}] Lua 成功，userId={} 在桶 {} 抢到商品 {}", threadName, userId, bucketIndex, productId);
                return result;
            } else if (result == 1L) {
                log.warn("[{}] Lua 表示重复下单 userId={}, productId={}", threadName, userId, productId);
                return result;
            } else if (result == 2L) {
                log.warn("[{}] Lua 表示总库存售罄 productId={}", threadName, productId);
                return result;
            } else if (result == 3L) {
                log.warn("[{}] Lua 指出桶 {} 已空，尝试下一个桶...", threadName, bucketIndex);
                // continue to next retry (尝试另一个桶)
            } else {
                log.warn("[{}] Lua 返回未知结果 {}，中止", threadName, result);
                break;
            }
        }

        if (result == 3L) {
            log.warn("[{}] 尝试 {} 次后，所有桶均空，返回库存不足", Thread.currentThread().getName(), BUCKET_RETRY_COUNT);
        }
        return result;
    }

    /**
     * ensureStockCacheIsReady: 三层缓存加载逻辑（Caffeine L1 + Redis L2 + DB）
     */
    private boolean ensureStockCacheIsReady(Long productId, String threadName) {
        String totalStockKey = STOCK_KEY_PREFIX + productId + TOTAL_STOCK_KEY_SUFFIX;

        log.info("[{}] 检查 L2 (Redis) 缓存: {}", threadName, totalStockKey);
        Object stockInRedis = redisTemplate.opsForValue().get(totalStockKey);
        if (stockInRedis != null) {
            log.info("[{}] L2 (Redis) 缓存命中！", threadName);
            return true;
        }

        String lockKey = LOCK_KEY_PREFIX + productId;
        log.warn("[{}] 缓存未命中，尝试获取 Redisson 锁: {}", threadName, lockKey);
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                log.info("[{}] 获得 Redisson 锁: {}", threadName, lockKey);
                try {
                    // DCL
                    stockInRedis = redisTemplate.opsForValue().get(totalStockKey);
                    if (stockInRedis != null) {
                        log.info("[{}] DCL 命中，缓存已加载。", threadName);
                        return true;
                    }

                    // L1 caffeine -> DB
                    Product product = self.getProductFromCache(productId);
                    if (product == null) {
                        log.warn("[{}] DB 中无商品，写空值防穿透", threadName);
                        redisTemplate.opsForValue().set(totalStockKey, -1, 5, TimeUnit.MINUTES);
                        return false;
                    }

                    long totalStock = product.getStock();
                    if (totalStock <= 0) {
                        log.warn("[{}] DB 库存为 0，写 -1 值", threadName);
                        redisTemplate.opsForValue().set(totalStockKey, -1, 5, TimeUnit.MINUTES);
                        return false;
                    }

                    // load buckets
                    long stockPerBucket = totalStock / BUCKET_COUNT;
                    long remainder = totalStock % BUCKET_COUNT;
                    long ttl = 30 * 60 + random.nextInt(30 * 60);

                    for (int i = 1; i <= BUCKET_COUNT; i++) {
                        long currentBucketStock = stockPerBucket;
                        if (i == BUCKET_COUNT) currentBucketStock += remainder;
                        redisTemplate.opsForValue().set(
                                STOCK_KEY_PREFIX + productId + BUCKET_KEY_SUFFIX + i,
                                currentBucketStock,
                                ttl, TimeUnit.SECONDS
                        );
                    }
                    redisTemplate.opsForValue().set(totalStockKey, totalStock, ttl, TimeUnit.SECONDS);
                    log.info("[{}] 缓存已加载至 Redis: productId={}, totalStock={}, ttl={}s", threadName, productId, totalStock, ttl);
                    return true;
                } finally {
                    lock.unlock();
                    log.info("[{}] 释放 Redisson 锁: {}", threadName, lockKey);
                }
            } else {
                log.warn("[{}] 获取 Redisson 锁超时: {}", threadName, lockKey);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[{}] Redisson 锁等待被中断", threadName, e);
            return false;
        }
    }

    @Cacheable(cacheNames = "product", key = "#productId", unless = "#result == null")
    public Product getProductFromCache(Long productId) {
        log.info("【三级缓存】L1 未命中，回源 DB 查询 productId={}", productId);
        return productRepository.findById(productId).orElse(null);
    }

    /**
     * 补偿：在 DB 写失败时恢复 Redis（增加回扣并移除 user flag）
     * 注意：补偿必须谨慎，建议把补偿任务写入可靠任务表并由专门任务/队列处理以便可重试与审计。
     */
    public void compensateRedisIncrease(Long productId, int bucketIndex, Long userId) {
        String bucketKey = STOCK_KEY_PREFIX + productId + BUCKET_KEY_SUFFIX + bucketIndex;
        String userSetKey = USER_SET_KEY_PREFIX + productId;

        try {
            // 增加桶库存
            redisTemplate.opsForValue().increment(bucketKey, 1);
            // 移除用户标记
            redisTemplate.opsForSet().remove(userSetKey, String.valueOf(userId));
            log.info("compensateRedisIncrease: productId={}, bucket={}, userId={} -> done", productId, bucketIndex, userId);
        } catch (Exception e) {
            // 记录失败需人工/自动化审计
            log.error("compensateRedisIncrease failed for productId={}, bucket={}, userId={}", productId, bucketIndex, userId, e);
            // 这里可以把补偿任务写入 DB outbox/dedicated table 以保证可靠重试
            throw new RuntimeException("redis compensation failed", e);
        }
    }

    /** 选择 bucket 的简单策略：优先用 deterministic（用户 hash）以减少随机带来的不确定性 */
    private int chooseBucket(Long productId, Long userId) {
        if (userId != null) {
            return (int) ((userId % BUCKET_COUNT) + 1);
        } else {
            return random.nextInt(BUCKET_COUNT) + 1;
        }
    }

    /** 根据 lua 返回码生成用户友好消息 */
    private String luaFailureMessage(Long code) {
        if (code == null) return "系统繁忙，请稍后";
        switch (code.intValue()) {
            case 1: return "重复下单";
            case 2: return "库存已售罄";
            case 3: return "当前库存不足，请稍后再试";
            default: return "秒杀失败，请稍后重试";
        }
    }
}
