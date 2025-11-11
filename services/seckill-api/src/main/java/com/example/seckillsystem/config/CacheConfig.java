// D:\Javaproject\seckill-api\src\main\java\com\example\seckillsystem\config\CacheConfig.java
package com.example.seckillsystem.config;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching // 【关键】启用 Spring 的 @Cacheable 功能
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

    // Caffeine 的 CacheManager 会由 Spring Boot 在 @EnableCaching 后自动配置
}