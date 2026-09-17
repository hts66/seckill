package com.example.seckill.cloud.order;

import com.example.seckill.cloud.common.ApiResponse;
import com.example.seckill.cloud.common.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/** 虚拟零钱钱包：余额查询/自助设置/流水，以及余额支付入口（替代收银台跳转）。 */
@RestController
@RequestMapping("/api")
public class WalletController {

    private final WalletService wallet;

    public WalletController(WalletService wallet) {
        this.wallet = wallet;
    }

    public record SetBalanceRequest(@NotNull @PositiveOrZero BigDecimal amount) {}

    @GetMapping("/wallet")
    ApiResponse<WalletService.WalletView> mine(HttpServletRequest request) {
        return ApiResponse.ok(wallet.getOrCreate(RequestUser.require(request).id()));
    }

    /** 演示环境：用户可随意设置自己的零钱余额。 */
    @PutMapping("/wallet/balance")
    ApiResponse<WalletService.WalletView> setBalance(@Valid @RequestBody SetBalanceRequest body,
                                                     HttpServletRequest request) {
        return ApiResponse.ok("余额已更新", wallet.setBalance(RequestUser.require(request).id(), body.amount()));
    }

    @GetMapping("/wallet/transactions")
    ApiResponse<List<WalletService.TxView>> transactions(HttpServletRequest request) {
        return ApiResponse.ok(wallet.transactions(RequestUser.require(request).id()));
    }

    /** 余额支付：同步完成扣款与订单状态翻转，无需轮询。 */
    @PostMapping("/orders/pay/{orderNo}")
    ApiResponse<WalletService.PayResult> pay(@PathVariable String orderNo, HttpServletRequest request) {
        return ApiResponse.ok("支付成功", wallet.pay(RequestUser.require(request).id(), orderNo));
    }
}
