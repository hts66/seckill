package com.example.seckill.cloud.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 支付宝沙箱配置（绑定 app.alipay.*）。未启用或关键参数缺失时，
 * PaymentService 走「仅落 PAYING 流水、不发起真实支付/退款」的降级路径。
 */
@ConfigurationProperties(prefix = "app.alipay")
public record AlipayProperties(
        boolean enabled,
        String appId,
        String privateKey,
        String alipayPublicKey,
        String gatewayUrl,
        String notifyUrl,
        String returnUrl) {

    public void requireReady() {
        if (!enabled || isBlank(appId) || isBlank(privateKey) || isBlank(alipayPublicKey)) {
            throw new IllegalStateException("支付宝支付未启用或关键配置缺失（app-id/private-key/alipay-public-key）");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
