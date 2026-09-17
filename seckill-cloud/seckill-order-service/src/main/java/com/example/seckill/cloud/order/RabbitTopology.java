package com.example.seckill.cloud.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
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
    /** 待支付订单超时关单：延迟队列（固定 TTL，死信回 seckill.events/order.close）+ 实际处理队列。 */
    public static final String CLOSE_DELAY_QUEUE = "seckill.order.close.delay";
    public static final String CLOSE_QUEUE = "seckill.order.close";
    public static final String CLOSE_KEY = "order.close";

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

    /** 建单消息进入后到期死信；TTL 取自 app.order.pay-timeout-seconds（默认 15 分钟）。 */
    @Bean
    Queue orderCloseDelayQueue(@Value("${app.order.pay-timeout-seconds:900}") int payTimeoutSeconds) {
        return QueueBuilder.durable(CLOSE_DELAY_QUEUE)
                .withArgument("x-message-ttl", (long) payTimeoutSeconds * 1000)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CLOSE_KEY)
                .build();
    }

    @Bean
    Queue orderCloseQueue() {
        return QueueBuilder.durable(CLOSE_QUEUE).build();
    }

    @Bean
    Binding orderCloseBinding() {
        return BindingBuilder.bind(orderCloseQueue()).to(exchange()).with(CLOSE_KEY);
    }

    @Bean
    Jackson2JsonMessageConverter converter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper.copy().findAndRegisterModules());
    }
}
