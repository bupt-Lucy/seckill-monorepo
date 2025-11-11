package com.example.seckillsystem.service.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties(prefix = "seckill")
public class SeckillProperties {

    /**
     * Redis key prefix for per-product bucketed stock counters.
     */
    private String stockKeyPrefix = "seckill:stock:";

    /**
     * Redis key prefix for the per-product user purchase marker set.
     */
    private String userSetKeyPrefix = "seckill:users:";

    /**
     * Redis key prefix for the Redis Stream outbox.
     */
    private String streamKeyPrefix = "seckill:stream:";

    /**
     * Number of buckets to hash users into.
     */
    private int bucketCount = 10;

    /**
     * Number of times to retry with different buckets when the chosen bucket is empty.
     */
    private int bucketRetryCount = 3;

    /**
     * Product identifiers exposed via the API (used to pre-warm Bloom filter).
     * 保留该属性以兼容旧版配置；如果同时配置 catalog，则会以 catalog 为准。
     */
    private List<Long> productIds = new ArrayList<>();

    /**
     * 通过配置文件提供商品元数据（包括初始库存），用于三级缓存的 L3 回源。
     */
    private List<ProductSpec> catalog = new ArrayList<>();

    /**
     * Redis 缓存 TTL（秒）。
     */
    private long cacheTtlSeconds = 30 * 60;

    /**
     * Redis 缓存 TTL 的随机抖动区间（秒），用于削峰。
     */
    private long cacheTtlJitterSeconds = 30 * 60;

    /**
     * 空值缓存 TTL（秒），用于缓存穿透防御。
     */
    private long emptyCacheTtlSeconds = 5 * 60;

    /**
     * 本地 Caffeine 缓存最大条数。
     */
    private long localCacheMaximumSize = 512;

    /**
     * 本地 Caffeine 缓存写入后过期时间（秒）。
     */
    private long localCacheExpireAfterWriteSeconds = 60;

    /**
     * 缓存重建锁的前缀。
     */
    private String cacheLockKeyPrefix = "lock:product:";

    /**
     * 获取重建锁时的等待秒数。
     */
    private long cacheLockWaitSeconds = 3;

    /**
     * 重建锁的租约秒数。
     */
    private long cacheLockLeaseSeconds = 10;

    public String getStockKeyPrefix() {
        return stockKeyPrefix;
    }

    public void setStockKeyPrefix(String stockKeyPrefix) {
        this.stockKeyPrefix = stockKeyPrefix;
    }

    public String getUserSetKeyPrefix() {
        return userSetKeyPrefix;
    }

    public void setUserSetKeyPrefix(String userSetKeyPrefix) {
        this.userSetKeyPrefix = userSetKeyPrefix;
    }

    public String getStreamKeyPrefix() {
        return streamKeyPrefix;
    }

    public void setStreamKeyPrefix(String streamKeyPrefix) {
        this.streamKeyPrefix = streamKeyPrefix;
    }

    public int getBucketCount() {
        return bucketCount;
    }

    public void setBucketCount(int bucketCount) {
        this.bucketCount = bucketCount;
    }

    public int getBucketRetryCount() {
        return bucketRetryCount;
    }

    public void setBucketRetryCount(int bucketRetryCount) {
        this.bucketRetryCount = bucketRetryCount;
    }

    public List<Long> getProductIds() {
        return Collections.unmodifiableList(productIds);
    }

    public void setProductIds(List<Long> productIds) {
        this.productIds = (productIds == null) ? new ArrayList<>() : new ArrayList<>(productIds);
    }

    public List<ProductSpec> getCatalog() {
        return catalog;
    }

    public void setCatalog(List<ProductSpec> catalog) {
        this.catalog = (catalog == null) ? new ArrayList<>() : new ArrayList<>(catalog);
        if (!this.catalog.isEmpty()) {
            this.productIds = this.catalog.stream()
                    .map(ProductSpec::getId)
                    .filter(id -> id != null)
                    .collect(Collectors.toList());
        }
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public long getCacheTtlJitterSeconds() {
        return cacheTtlJitterSeconds;
    }

    public void setCacheTtlJitterSeconds(long cacheTtlJitterSeconds) {
        this.cacheTtlJitterSeconds = cacheTtlJitterSeconds;
    }

    public long getEmptyCacheTtlSeconds() {
        return emptyCacheTtlSeconds;
    }

    public void setEmptyCacheTtlSeconds(long emptyCacheTtlSeconds) {
        this.emptyCacheTtlSeconds = emptyCacheTtlSeconds;
    }

    public long getLocalCacheMaximumSize() {
        return localCacheMaximumSize;
    }

    public void setLocalCacheMaximumSize(long localCacheMaximumSize) {
        this.localCacheMaximumSize = localCacheMaximumSize;
    }

    public long getLocalCacheExpireAfterWriteSeconds() {
        return localCacheExpireAfterWriteSeconds;
    }

    public void setLocalCacheExpireAfterWriteSeconds(long localCacheExpireAfterWriteSeconds) {
        this.localCacheExpireAfterWriteSeconds = localCacheExpireAfterWriteSeconds;
    }

    public String getCacheLockKeyPrefix() {
        return cacheLockKeyPrefix;
    }

    public void setCacheLockKeyPrefix(String cacheLockKeyPrefix) {
        this.cacheLockKeyPrefix = cacheLockKeyPrefix;
    }

    public long getCacheLockWaitSeconds() {
        return cacheLockWaitSeconds;
    }

    public void setCacheLockWaitSeconds(long cacheLockWaitSeconds) {
        this.cacheLockWaitSeconds = cacheLockWaitSeconds;
    }

    public long getCacheLockLeaseSeconds() {
        return cacheLockLeaseSeconds;
    }

    public void setCacheLockLeaseSeconds(long cacheLockLeaseSeconds) {
        this.cacheLockLeaseSeconds = cacheLockLeaseSeconds;
    }

    public Optional<ProductSpec> lookupProductSpec(Long productId) {
        if (productId == null) {
            return Optional.empty();
        }
        return catalog.stream()
                .filter(spec -> productId.equals(spec.getId()))
                .findFirst();
    }

    public List<Long> resolvedProductIds() {
        if (!catalog.isEmpty()) {
            return Collections.unmodifiableList(catalog.stream()
                    .map(ProductSpec::getId)
                    .filter(id -> id != null)
                    .collect(Collectors.toList()));
        }
        return Collections.unmodifiableList(productIds);
    }

    public static class ProductSpec {
        private Long id;
        private long stock;
        private String title;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getStock() {
            return stock;
        }

        public void setStock(long stock) {
            this.stock = stock;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }
}
