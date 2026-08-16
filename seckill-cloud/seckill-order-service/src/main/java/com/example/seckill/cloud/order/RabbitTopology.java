package com.example.seckill.cloud.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {
    public static final String EXCHANGE = "seckill.events";
    public static final String ORDER_QUEUE = "seckill.order.created";
    public static final String ORDER_KEY = "order.created";
    public static final String COMP_QUEUE = "seckill.stock.compensate";
    public static final String COMP_KEY = "stock.compensate";
    public static final String DEAD_EXCHANGE = "seckill.dead";
    public static final String DEAD_QUEUE = "seckill.order.dead";
    public static final String DEAD_KEY = "order.dead";

    @Bean
    DirectExchange exchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue orderQueue() {
        // 该队列已在现有环境创建，保持参数兼容；新环境可按部署脚本追加死信参数。
        return QueueBuilder.durable(ORDER_QUEUE).build();
    }

    @Bean
    Binding orderBinding() {
        return BindingBuilder.bind(orderQueue()).to(exchange()).with(ORDER_KEY);
    }

    @Bean
    Queue compensationQueue() {
        return QueueBuilder.durable(COMP_QUEUE).build();
    }

    @Bean
    Binding compensationBinding() {
        return BindingBuilder.bind(compensationQueue()).to(exchange()).with(COMP_KEY);
    }

    @Bean
    DirectExchange deadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    Queue deadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    Binding deadBinding() {
        return BindingBuilder.bind(deadQueue()).to(deadExchange()).with(DEAD_KEY);
    }

    @Bean
    Jackson2JsonMessageConverter converter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper.copy().findAndRegisterModules());
    }
}
