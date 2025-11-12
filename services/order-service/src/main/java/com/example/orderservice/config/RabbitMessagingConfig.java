package com.example.orderservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMessagingConfig {

    @Bean
    public Declarables seckillDeclarables(@Value("${order.rabbitmq.exchange:seckill.exchange}") String exchangeName,
                                          @Value("${order.rabbitmq.queue:seckill.order.queue}") String queueName,
                                          @Value("${order.rabbitmq.routing-key:seckill.order.created}") String routingKey) {
        TopicExchange exchange = new TopicExchange(exchangeName, true, false);
        Queue queue = new Queue(queueName, true);
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(routingKey);
        return new Declarables(exchange, queue, binding);
    }
}
