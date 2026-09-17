package com.example.seckill.cloud.order;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 消费 TTL 死信：触发超时未支付订单关单与库存回补。共享队列，多实例竞争，CAS 保证幂等。 */
@Component
public class OrderCloseConsumer {

    private final OrderLifecycleService lifecycle;

    public OrderCloseConsumer(OrderLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @RabbitListener(queues = RabbitTopology.CLOSE_QUEUE)
    public void onClose(OrderCloseMessage message) {
        lifecycle.autoClose(message);
    }
}
