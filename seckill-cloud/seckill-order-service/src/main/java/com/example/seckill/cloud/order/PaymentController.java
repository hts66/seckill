package com.example.seckill.cloud.order;

import com.example.seckill.cloud.common.ApiResponse;
import com.example.seckill.cloud.common.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** 模拟支付：点击「立即支付」直接把订单标记为已支付，不再跳转支付宝收银台。 */
    @PostMapping("/api/orders/pay/{orderNo}")
    ApiResponse<String> pay(@PathVariable String orderNo, HttpServletRequest request) {
        return ApiResponse.ok(payments.pay(RequestUser.require(request).id(), orderNo), null);
    }
}
