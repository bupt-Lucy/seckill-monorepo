package com.example.seckillsystem.controller; // 注意包名

import com.example.seckillsystem.client.OrderServiceClient;
import com.example.seckillsystem.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestFeignController {

    @Autowired
    private OrderServiceClient orderServiceClient;

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    @GetMapping("/test-feign/{message}")
    public String testFeign(@PathVariable String message) {
        log.info("即将通过 Feign 调用 order-service...");
        // 像调用本地方法一样调用远程服务
        String response = orderServiceClient.echo(message);
        log.info("收到 order-service 的回复: {}", response);
        return "Feign Call OK: " + response;
    }
}