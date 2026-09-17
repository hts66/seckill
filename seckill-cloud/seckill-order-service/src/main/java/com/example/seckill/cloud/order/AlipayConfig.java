package com.example.seckill.cloud.order;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 支付宝客户端装配：仅在 app.alipay.enabled=true 且配置完整时创建，PaymentService 用 ObjectProvider 可选注入。 */
@Configuration
@EnableConfigurationProperties(AlipayProperties.class)
public class AlipayConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.alipay", name = "enabled", havingValue = "true")
    public AlipayClient alipayClient(AlipayProperties props) {
        props.requireReady();
        return new DefaultAlipayClient(
                props.gatewayUrl(), props.appId(), props.privateKey(),
                "json", "UTF-8", props.alipayPublicKey(), "RSA2");
    }
}
