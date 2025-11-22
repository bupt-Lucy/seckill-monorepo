package com.example.orderservice.controller; // 注意包名

import com.example.orderservice.consumer.OrderProcessingService;
import com.example.orderservice.exception.SeckillBusinessException;
import com.example.orderservice.model.SeckillOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
public class OrderEchoController {

    // 从 Nacos 配置中心读取自己的端口号
    @Value("${server.port}")
    private String serverPort;

    private static final Logger log = LoggerFactory.getLogger(OrderProcessingService.class);

    @GetMapping("/api/v1/order/echo/{message}")
    public String echo(@PathVariable String message) {
        log.info("收到了来自 seckill-api 的 Feign 请求: {}", message);
        return "Hello from order-service on port " + serverPort + ", I received: " + message;
    }
}

