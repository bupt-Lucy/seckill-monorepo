package com.example.seckillsystem.service;

import com.example.seckillsystem.model.Product; // 确保 import 正确
import com.example.seckillsystem.repository.ProductRepository;
import com.google.common.hash.BloomFilter; // 【新增】
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RedisPreheatService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RedisPreheatService.class);

    // ... Key 常量 ...

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private BloomFilter<Long> productBloomFilter; // 【新增】注入布隆过滤器

    @Override
    public void run(String... args) throws Exception {
        log.info("=========================================");
        log.info("V5.2 缓存预热任务开始...");

        // 1. 【废弃库存预热】
        //    我们不再启动时全量预热库存，改为“懒加载”
        //    (删除所有 redis.opsForValue().set(...) 的代码)

        // 2. 【新增】预热布隆过滤器
        List<Product> products = productRepository.findAll(); // 假设从数据库获取所有商品
        if (products.isEmpty()) {
            log.warn("没有找到商品，布隆过滤器未预热。");
            return;
        }

        for (Product product : products) {
            productBloomFilter.put(product.getId()); // 将每一个合法的 productId 放入过滤器
        }

        log.info("布隆过滤器预热完毕。共加载 {} 个商品ID。", products.size());
        log.info("=========================================");
    }
}