package com.example.seckillsystem.service;

import com.example.seckillsystem.service.dto.SeckillResult;
import com.example.seckillsystem.service.inventory.InventoryCacheFacade;
import com.example.seckillsystem.service.inventory.ProductInventorySnapshot;
import com.example.seckillsystem.service.props.SeckillProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private static final String TOTAL_STOCK_SUFFIX = ":total";
    private static final String BUCKET_SUFFIX = ":bucket_";

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Long> seckillScript;
    private final SeckillProperties properties;
    private final InventoryCacheFacade inventoryCacheFacade;
    private final RedissonClient redissonClient;
    private final Random random = new Random();

    public SeckillService(RedisTemplate<String, Object> redisTemplate,
                          @Qualifier("seckillScriptV5") DefaultRedisScript<Long> seckillScript,
                          SeckillProperties properties,
                          InventoryCacheFacade inventoryCacheFacade,
                          RedissonClient redissonClient) {
        this.redisTemplate = redisTemplate;
        this.seckillScript = seckillScript;
        this.properties = properties;
        this.inventoryCacheFacade = inventoryCacheFacade;
        this.redissonClient = redissonClient;
    }

    /**
     * Submit a seckill request. Redis 层完成原子扣减，并通过 Redis Stream 写入 outbox。
     * 缓存三防：布隆过滤器（Controller 层）、L1 Caffeine（InventoryCacheFacade）以及此处的
     * L2 Redis + Redisson 锁/空值缓存策略。
     */
    public SeckillResult submitSeckillOrder(Long productId, Long userId) {
        CacheWarmupState warmupState = ensureStockCacheIsReady(productId);
        switch (warmupState) {
            case NOT_FOUND:
                log.warn("Product {} not found during cache warmup", productId);
                return SeckillResult.notFound();
            case SOLD_OUT:
                log.info("Product {} already sold out before executing Lua", productId);
                return SeckillResult.soldOut();
            case LOADING:
                log.debug("Product {} cache is being rebuilt by another worker", productId);
                return SeckillResult.notReady();
            case READY:
                break;
            default:
                log.error("Unexpected cache warmup state {} for product {}", warmupState, productId);
                return SeckillResult.error("系统繁忙，请稍后重试");
        }

        String requestId = UUID.randomUUID().toString();
        String threadName = Thread.currentThread().getName();

        int retryCount = Math.max(1, properties.getBucketRetryCount());
        for (int attempt = 0; attempt < retryCount; attempt++) {
            int bucketIndex = chooseBucket(productId, userId, attempt);
            Long luaResult = executeLua(productId, userId, bucketIndex, requestId);

            if (luaResult == null) {
                log.error("[{}] Lua execution returned null (productId={}, userId={}, bucket={})",
                        threadName, productId, userId, bucketIndex);
                return SeckillResult.error("系统繁忙，请稍后再试");
            }

            switch (luaResult.intValue()) {
                case 0:
                    log.info("[{}] Lua success -> queued requestId={}, productId={}, userId={}, bucket={}",
                            threadName, requestId, productId, userId, bucketIndex);
                    return SeckillResult.queued(requestId);
                case 1:
                    log.warn("[{}] Duplicate request detected productId={}, userId={}",
                            threadName, productId, userId);
                    return SeckillResult.duplicate();
                case 2:
                    log.warn("[{}] Total stock sold out productId={}", threadName, productId);
                    return SeckillResult.soldOut();
                case 3:
                    log.debug("[{}] Bucket {} empty for productId={}, retry attempt {}/{}", threadName,
                            bucketIndex, productId, attempt + 1, retryCount);
                    // try another bucket if we still have attempts left
                    break;
                default:
                    log.error("[{}] Unexpected Lua return code {} for productId={}, userId={}, bucket={}",
                            threadName, luaResult, productId, userId, bucketIndex);
                    return SeckillResult.error("秒杀失败，请稍后重试");
            }
        }

        log.warn("All buckets exhausted for productId={} userId={}", productId, userId);
        return SeckillResult.bucketEmpty();
    }

    private CacheWarmupState ensureStockCacheIsReady(Long productId) {
        String threadName = Thread.currentThread().getName();
        String totalKey = totalStockKey(productId);

        Object cachedTotal = redisTemplate.opsForValue().get(totalKey);
        if (cachedTotal != null) {
            long current = asLong(cachedTotal);
            if (current <= 0) {
                log.debug("[{}] Redis sentinel indicates product {} is sold out", threadName, productId);
                return CacheWarmupState.SOLD_OUT;
            }
            return CacheWarmupState.READY;
        }

        String lockKey = properties.getCacheLockKeyPrefix() + productId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(properties.getCacheLockWaitSeconds(),
                    properties.getCacheLockLeaseSeconds(), TimeUnit.SECONDS);
            if (!locked) {
                log.debug("[{}] Failed to acquire rebuild lock for product {}", threadName, productId);
                return CacheWarmupState.LOADING;
            }

            cachedTotal = redisTemplate.opsForValue().get(totalKey);
            if (cachedTotal != null) {
                long current = asLong(cachedTotal);
                if (current <= 0) {
                    return CacheWarmupState.SOLD_OUT;
                }
                return CacheWarmupState.READY;
            }

            ProductInventorySnapshot snapshot = inventoryCacheFacade.load(productId);
            if (snapshot == null) {
                log.warn("Product {} not found in L3 data source; caching empty sentinel", productId);
                redisTemplate.opsForValue().set(totalKey, -1L,
                        properties.getEmptyCacheTtlSeconds(), TimeUnit.SECONDS);
                return CacheWarmupState.NOT_FOUND;
            }

            long totalStock = snapshot.totalStock();
            if (totalStock <= 0) {
                log.info("Product {} has no remaining stock in L3 data source; caching sold-out sentinel", productId);
                redisTemplate.opsForValue().set(totalKey, -1L,
                        properties.getEmptyCacheTtlSeconds(), TimeUnit.SECONDS);
                return CacheWarmupState.SOLD_OUT;
            }

            long bucketCount = Math.max(1, properties.getBucketCount());
            long base = totalStock / bucketCount;
            long remainder = totalStock % bucketCount;
            long ttl = cacheTtlSeconds();

            for (int i = 1; i <= bucketCount; i++) {
                long bucketStock = base + ((i == bucketCount) ? remainder : 0);
                redisTemplate.opsForValue().set(bucketKey(productId, i),
                        Math.max(bucketStock, 0), ttl, TimeUnit.SECONDS);
            }
            redisTemplate.opsForValue().set(totalKey, totalStock, ttl, TimeUnit.SECONDS);

            log.info("[{}] Rebuilt Redis cache for product {} with totalStock={}, ttl={}s",
                    threadName, productId, totalStock, ttl);
            return CacheWarmupState.READY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[{}] Interrupted while waiting for cache rebuild lock", threadName, e);
            return CacheWarmupState.LOADING;
        } finally {
            if (locked) {
                try {
                    lock.unlock();
                } catch (IllegalMonitorStateException ignored) {
                    // lock already released; ignore
                }
            }
        }
    }

    private Long executeLua(Long productId, Long userId, int bucketIndex, String requestId) {
        List<String> keys = Arrays.asList(
                properties.getStockKeyPrefix(),
                properties.getUserSetKeyPrefix(),
                properties.getStreamKeyPrefix()
        );

        try {
            return redisTemplate.execute(
                    seckillScript,
                    keys,
                    String.valueOf(userId),
                    String.valueOf(productId),
                    String.valueOf(bucketIndex),
                    requestId
            );
        } catch (Exception e) {
            log.error("Lua execution threw exception productId={}, userId={}, bucket={}",
                    productId, userId, bucketIndex, e);
            return null;
        }
    }

    private int chooseBucket(Long productId, Long userId, int attempt) {
        int bucketCount = Math.max(1, properties.getBucketCount());
        if (userId != null) {
            long base = Math.floorMod(userId + attempt, bucketCount);
            return (int) base + 1;
        }
        return random.nextInt(bucketCount) + 1;
    }

    private long cacheTtlSeconds() {
        long base = Math.max(1, properties.getCacheTtlSeconds());
        long jitter = Math.max(0, properties.getCacheTtlJitterSeconds());
        if (jitter == 0) {
            return base;
        }
        return base + random.nextInt((int) Math.max(1, jitter));
    }

    private String totalStockKey(Long productId) {
        return properties.getStockKeyPrefix() + productId + TOTAL_STOCK_SUFFIX;
    }

    private String bucketKey(Long productId, int bucketIndex) {
        return properties.getStockKeyPrefix() + productId + BUCKET_SUFFIX + bucketIndex;
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private enum CacheWarmupState {
        READY,
        SOLD_OUT,
        NOT_FOUND,
        LOADING
    }
}
