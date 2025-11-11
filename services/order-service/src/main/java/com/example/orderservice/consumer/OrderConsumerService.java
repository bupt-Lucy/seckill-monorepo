package com.example.orderservice.consumer;

// ... (imports: SeckillBusinessException, Repositories, Models, Logger, Map) ...
import com.example.orderservice.exception.SeckillBusinessException;
import com.example.orderservice.model.SeckillOrder;
import com.example.orderservice.repository.ProductRepository;
import com.example.orderservice.repository.SeckillOrderRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
// 【核心改动】
@RocketMQMessageListener(
        consumerGroup = "order-consumer-group", // 对应 properties 里的 group
        topic = "seckill-order-topic" // 消息的主题
)
public class OrderConsumerService implements RocketMQListener<Map<String, Long>> {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumerService.class);

    @Autowired
    private SeckillOrderRepository orderRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired @Lazy
    private OrderConsumerService self;

    private final Map<Long, BigDecimal> productPriceMap = Map.of(1L, new BigDecimal("1.00"));

    /**
     * 【核心改动】这是 RocketMQListener 接口要求我们实现的方法
     */
    @Override
    @CircuitBreaker(name = "dbWrite", fallbackMethod = "fallbackForCreateOrder")
    public void onMessage(Map<String, Long> orderMessage) {
        log.info("从 RocketMQ 接收到订单消息: {}", orderMessage);

        // 【V5.3 逻辑不变】
        // 直接调用，让所有异常（业务/系统）都抛出
        // RocketMQ 会捕获异常，自动重试，重试16次失败后自动扔进 DLQ
        self.createOrderInDb(orderMessage);
    }

    /**
     * 内部事务方法 (保持不变)
     */
    @Transactional
    public void createOrderInDb(Map<String, Long> orderMessage) {
        // (这个方法的内部逻辑保持不变，它会自然地抛出异常)

        Long userId = orderMessage.get("userId");
        Long productId = orderMessage.get("productId");

        // 1. 检查重复下单
        if (orderRepository.findByUserIdAndProductId(userId, productId) != null) {
            throw new SeckillBusinessException("您已秒杀过此商品");
        }

        // 2. 扣减 MySQL 库存
        int result = productRepository.deductStock(productId);
        if (result == 0) {
            throw new SeckillBusinessException("MySQL库存扣减失败或已售罄");
        }

        // 3. 创建订单
        SeckillOrder order = new SeckillOrder();
        order.setUserId(userId);
        order.setProductId(productId);
        order.setOrderPrice(productPriceMap.getOrDefault(productId, new BigDecimal("0.00")));
        orderRepository.save(order);

        log.info("数据库订单创建成功，事务即将提交。");
    }

    /**
     * 降级方法 (签名需要匹配 onMessage)
     * 【V5.3 核心改动】
     */
    public void fallbackForCreateOrder(Map<String, Long> orderMessage, Throwable t) {
        log.error("数据库写入熔断器已打开！执行降级逻辑。 订单: {}, 异常: {}", orderMessage, t.getMessage());

        // 【重要】必须抛出异常，才能触发 RocketMQ 的重试和 DLQ
        throw new RuntimeException("熔断器已打开，执行降级", t);
    }
}