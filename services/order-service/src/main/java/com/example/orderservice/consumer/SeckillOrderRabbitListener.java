package com.example.orderservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class SeckillOrderRabbitListener {

    private final ObjectMapper objectMapper;
    private final OrderProcessingService processingService;

    public SeckillOrderRabbitListener(ObjectMapper objectMapper,
                                      OrderProcessingService processingService) {
        this.objectMapper = objectMapper;
        this.processingService = processingService;
    }

    @RabbitListener(queues = "${order.rabbitmq.queue:seckill.order.queue}")
    public void handle(@Payload String payload) throws Exception {
        OrderStreamMessage message = objectMapper.readValue(payload, OrderStreamMessage.class);
        processingService.handle(message);
    }
}
