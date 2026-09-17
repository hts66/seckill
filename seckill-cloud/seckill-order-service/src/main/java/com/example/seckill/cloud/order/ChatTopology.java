package com.example.seckill.cloud.order;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天跨实例扇出拓扑：
 *   所有 order-service 实例各自声明一个【独占、自动删除】队列绑定到 fanout 交换机。
 *   一条消息落库后发到交换机，每个实例都能收到并推给自己本机上的 WebSocket 连接，
 *   因此网关无需 sticky session，实例可随意扩缩。
 *   实时消息不做 MQ 持久化（MySQL 里已有历史），实例短暂离线期间的消息靠 REST 历史补偿。
 */
@Configuration
public class ChatTopology {
    public static final String FANOUT_EXCHANGE = "chat.fanout";
    public static final String QUEUE_BEAN = "chatFanoutQueue";

    @Bean
    public FanoutExchange chatFanoutExchange() {
        return new FanoutExchange(FANOUT_EXCHANGE, true, false);
    }

    @Bean(QUEUE_BEAN)
    public Queue chatFanoutQueue(ChatInstance instance) {
        // exclusive=true：队列绑定在声明它的连接上，连接断开（实例挂掉）队列自动删除
        return new Queue("chat.fanout." + instance.id(), false, true, true);
    }

    @Bean
    public Binding chatFanoutBinding(Queue chatFanoutQueue, FanoutExchange chatFanoutExchange) {
        return BindingBuilder.bind(chatFanoutQueue).to(chatFanoutExchange);
    }
}
