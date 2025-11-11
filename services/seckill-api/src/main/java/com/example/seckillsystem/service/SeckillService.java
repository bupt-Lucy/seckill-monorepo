package com.example.seckillsystem.service;

import com.example.seckillsystem.service.dto.SeckillResult;
import com.example.seckillsystem.service.props.SeckillProperties;
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

@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Long> seckillScript;
    private final SeckillProperties properties;
    private final Random random = new Random();

    public SeckillService(RedisTemplate<String, Object> redisTemplate,
                          @Qualifier("seckillScriptV5") DefaultRedisScript<Long> seckillScript,
                          SeckillProperties properties) {
        this.redisTemplate = redisTemplate;
        this.seckillScript = seckillScript;
        this.properties = properties;
    }

    /**
     * Submit a seckill request. The Lua script performs an atomic
     * "decrement bucket -> decrement total -> mark user -> append stream".
     *
     * @return {@link SeckillResult} describing how the request was handled.
     */
    public SeckillResult submitSeckillOrder(Long productId, Long userId) {
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
}
