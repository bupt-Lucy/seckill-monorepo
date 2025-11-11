package com.example.seckillsystem.service.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
     */
    private List<Long> productIds = new ArrayList<>();

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
        return productIds;
    }

    public void setProductIds(List<Long> productIds) {
        this.productIds = productIds;
    }
}
