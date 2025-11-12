package com.example.seckillsystem.config;

import com.example.seckillsystem.service.props.SeckillProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * 创建一个布隆过滤器 Bean
     * @return
     */
    @Bean
    public BloomFilter<Long> productBloomFilter() {
        // Funnels.longFunnel(): 告诉布隆过滤器我们存的是 Long 类型
        // 10000:           预期插入的元素数量（比如 1 万个商品）
        // 0.01:            期望的误判率（1%）
        // 误判率越低，需要的内存空间就越大
        return BloomFilter.create(Funnels.longFunnel(), 10000, 0.01);
    }

    @Bean
    public CacheManager caffeineCacheManager(SeckillProperties properties) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("inventory");
        cacheManager.setAllowNullValues(false);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(properties.getLocalCacheMaximumSize())
                .expireAfterWrite(properties.getLocalCacheExpireAfterWriteSeconds(), TimeUnit.SECONDS));
        return cacheManager;
    }
}