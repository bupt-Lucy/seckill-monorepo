package com.example.orderservice.consumer;

import com.example.orderservice.exception.SeckillBusinessException;
import com.example.orderservice.model.SeckillOrder;
import com.example.orderservice.repository.ProductRepository;
import com.example.orderservice.repository.SeckillOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;

@Service
public class OrderProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessingService.class);

    private final SeckillOrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderProcessingService(SeckillOrderRepository orderRepository,
                                  ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public void handle(OrderStreamMessage message) {
        if (orderRepository.findByRequestId(message.getRequestId()).isPresent()) {
            log.info("Request {} already processed, skipping", message.getRequestId());
            return;
        }

        if (orderRepository.findByUserIdAndProductId(message.getUserId(), message.getProductId()) != null) {
            log.warn("Duplicate purchase detected userId={} productId={}", message.getUserId(), message.getProductId());
            return;
        }

        int updated = productRepository.deductStock(message.getProductId());
        if (updated == 0) {
            throw new SeckillBusinessException("MySQL库存扣减失败或已售罄");
        }

        SeckillOrder order = new SeckillOrder();
        order.setRequestId(message.getRequestId());
        order.setUserId(message.getUserId());
        order.setProductId(message.getProductId());
        order.setOrderPrice(defaultPrice(message.getProductId()));
        order.setCreateTime(new Date());

        try {
            orderRepository.save(order);
        } catch (DataIntegrityViolationException ex) {
            // Unique constraint on requestId safeguards against duplicates
            log.warn("Duplicate request detected at DB layer for requestId={}", message.getRequestId());
        }

        log.info("Order persisted for requestId={} userId={} productId={}",
                message.getRequestId(), message.getUserId(), message.getProductId());
    }

    private BigDecimal defaultPrice(Long productId) {
        // Simplified pricing (could be replaced with a proper lookup)
        return BigDecimal.ONE;
    }
}
