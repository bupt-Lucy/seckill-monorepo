package com.example.orderservice.repository;

import com.example.orderservice.model.SeckillOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SeckillOrderRepository extends JpaRepository<SeckillOrder, Long> {
    // 根据用户ID和商品ID查询订单，用于判断是否重复秒杀
    SeckillOrder findByUserIdAndProductId(Long userId, Long productId);

    Optional<SeckillOrder> findByRequestId(String requestId);
}