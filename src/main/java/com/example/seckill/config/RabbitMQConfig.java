package com.example.seckill.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // ---- 秒杀下单交换机 + 队列 ----
    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    public static final String SECKILL_QUEUE = "seckill.order.queue";
    public static final String SECKILL_ROUTING_KEY = "seckill.order";

    // ---- 死信队列（订单超时取消） ----
    public static final String DEAD_EXCHANGE = "seckill.dead.exchange";
    public static final String DEAD_QUEUE = "seckill.dead.queue";
    public static final String DEAD_ROUTING_KEY = "seckill.dead";
    public static final String DELAY_QUEUE = "seckill.order.delay.queue";
    public static final String DELAY_ROUTING_KEY = "seckill.order.delay";

    // --------------- 秒杀下单队列 ---------------
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_QUEUE).build();
    }

    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillExchange())
                .with(SECKILL_ROUTING_KEY);
    }

    // --------------- 延迟队列（订单超时取消） ---------------
    @Bean
    public DirectExchange deadExchange() {
        return new DirectExchange(DEAD_EXCHANGE);
    }

    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue())
                .to(deadExchange())
                .with(DEAD_ROUTING_KEY);
    }

    /**
     * 延迟队列：消息过期后自动转发到死信队列
     * 延迟时间在发送消息时通过 setExpiration 指定（毫秒）
     */
    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue())
                .to(seckillExchange())
                .with(DELAY_ROUTING_KEY);
    }

    // --------------- JSON 序列化 ---------------
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
