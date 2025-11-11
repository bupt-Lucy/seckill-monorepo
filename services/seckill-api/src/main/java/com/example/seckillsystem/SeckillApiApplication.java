package com.example.seckillsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableDiscoveryClient // 【新增】激活 Nacos 服务发现
@EnableFeignClients // 【新增】激活 Feign 客户端功能
@EnableCaching
public class SeckillApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeckillApiApplication.class, args);
    }
}