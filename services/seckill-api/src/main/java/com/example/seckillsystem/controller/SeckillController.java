package com.example.seckillsystem.controller;

import com.example.seckillsystem.service.dto.SeckillResult;
import com.example.seckillsystem.service.SeckillService;
import com.google.common.hash.BloomFilter; // 【新增】
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity; // 【新增】
import org.springframework.web.bind.annotation.*;

@RestController
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    private static final Logger log = LoggerFactory.getLogger(SeckillController.class);

    @Autowired
    private BloomFilter<Long> productBloomFilter; // 【新增】注入布隆过滤器

    @PostMapping("/seckill/{productId}")
    public ResponseEntity<String> submitSeckillOrder(@PathVariable Long productId,
                                                     @RequestHeader("X-User-Id") Long userId) {

        // 【V5.2 核心改动：防穿透】
        // 1. 使用布隆过滤器检查 productId 是否“可能”存在
        if (!productBloomFilter.mightContain(productId)) {
            log.warn("【防穿透】布隆过滤器拦截到不存在的商品ID: {}", productId);
            // 直接拒绝，不给后续任何机会
            return ResponseEntity.badRequest().body("商品不存在或活动未开始");
        }

        // 2. （可选）引入 Caffeine 本地缓存，检查“是否已售罄”
        // ... (这部分是 V5.1 的优化，我们先专注 V5.2 核心) ...

        // 3. 通过布隆过滤器后，才进入核心秒杀逻辑
        SeckillResult result = seckillService.submitSeckillOrder(productId, userId);

        if (result.isAccepted()) {
            return ResponseEntity.accepted().body(result.getMessage());
        }

        switch (result.getCode()) {
            case "DUPLICATE":
                return ResponseEntity.status(409).body(result.getMessage());
            case "SOLD_OUT":
                return ResponseEntity.status(410).body(result.getMessage());
            case "BUCKET_EMPTY":
                return ResponseEntity.status(429).body(result.getMessage());
            default:
                return ResponseEntity.status(500).body(result.getMessage());
        }
    }

    // ... (你其他的接口，比如 /test-feign 和 /seckill/stock) ...
}