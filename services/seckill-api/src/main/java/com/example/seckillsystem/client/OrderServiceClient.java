package com.example.seckillsystem.client; // 注意包名

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "order-service") // 对应 Nacos 上的服务名
public interface OrderServiceClient {

    // 声明一个“模拟”的接口，用于测试
    @GetMapping("/api/v1/order/echo/{message}")
    String echo(@PathVariable("message") String message);
}