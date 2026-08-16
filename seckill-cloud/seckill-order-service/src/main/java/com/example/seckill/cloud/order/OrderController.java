package com.example.seckill.cloud.order;

import com.example.seckill.cloud.common.ApiResponse;
import com.example.seckill.cloud.common.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
public class OrderController {
    private static final String ORDER_COLUMNS = "id,order_no,user_id,item_id,amount,status,fulfillment_status,created_at," +
            "address_id,receiver_name,receiver_phone,receiver_province,receiver_city,receiver_district,receiver_detail,shipping_time";
    private final JdbcClient jdbc;

    public OrderController(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record OrderView(Long id, String orderNo, Long userId, Long itemId, BigDecimal amount,
                            Integer status, Integer fulfillmentStatus, LocalDateTime createdAt, Long addressId, String receiverName,
                            String receiverPhone, String receiverProvince, String receiverCity,
                            String receiverDistrict, String receiverDetail, LocalDateTime shippingTime) {}

    public record AdminOrderView(Long id, String orderNo, Long userId, String buyerName,
                                 String buyerEmail, Long itemId, BigDecimal amount, Integer status, Integer fulfillmentStatus,
                                 LocalDateTime createdAt, String receiverName, String receiverPhone,
                                 String receiverProvince, String receiverCity, String receiverDistrict,
                                 String receiverDetail, LocalDateTime shippingTime) {}

    public record BindAddressRequest(@NotNull Long addressId) {}

    @GetMapping("/api/orders")
    ApiResponse<List<OrderView>> mine(HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        return ApiResponse.ok(jdbc.sql("SELECT " + ORDER_COLUMNS +
                        " FROM seckill_orders WHERE user_id=:userId ORDER BY id DESC")
                .param("userId", userId).query(OrderView.class).list());
    }

    @GetMapping("/api/orders/{orderNo}")
    ApiResponse<OrderView> one(@PathVariable String orderNo, HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        return ApiResponse.ok(jdbc.sql("SELECT " + ORDER_COLUMNS +
                        " FROM seckill_orders WHERE order_no=:orderNo AND user_id=:userId")
                .param("orderNo", orderNo).param("userId", userId).query(OrderView.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("订单不存在")));
    }

    @PostMapping("/api/orders/pay/{orderNo}")
    ApiResponse<Void> pay(@PathVariable String orderNo, HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        jdbc.sql("UPDATE seckill_orders SET status=1,fulfillment_status=CASE WHEN receiver_name IS NULL THEN 0 ELSE 1 END,pay_time=NOW() " +
                        "WHERE order_no=:orderNo AND user_id=:userId AND status=0")
                .param("orderNo", orderNo).param("userId", userId).update();
        return ApiResponse.ok("支付成功", null);
    }

    @PutMapping("/api/orders/{orderNo}/address")
    @Transactional
    ApiResponse<OrderView> bindAddress(@PathVariable String orderNo,
                                       @Valid @RequestBody BindAddressRequest body,
                                       HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        AddressRow address = jdbc.sql("SELECT id,receiver_name,receiver_phone,province,city,district,detail " +
                        "FROM user_addresses WHERE id=:addressId AND user_id=:userId")
                .param("addressId", body.addressId()).param("userId", userId)
                .query(AddressRow.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("收货地址不存在或不属于当前用户"));
        int changed = jdbc.sql("UPDATE seckill_orders SET address_id=:addressId,receiver_name=:name,receiver_phone=:phone," +
                        "receiver_province=:province,receiver_city=:city,receiver_district=:district,receiver_detail=:detail," +
                        "fulfillment_status=CASE WHEN status=1 THEN 1 ELSE 0 END " +
                        "WHERE order_no=:orderNo AND user_id=:userId AND status IN (0,1) AND fulfillment_status < 2")
                .param("addressId", address.id()).param("name", address.receiverName()).param("phone", address.receiverPhone())
                .param("province", address.province()).param("city", address.city()).param("district", address.district())
                .param("detail", address.detail()).param("orderNo", orderNo).param("userId", userId).update();
        if (changed == 0) throw new IllegalStateException("订单不存在、已取消或已发货，无法修改地址");
        return one(orderNo, request);
    }

    @PostMapping("/api/orders/cancel/{orderNo}")
    ApiResponse<Void> cancel(@PathVariable String orderNo, HttpServletRequest request) {
        Long userId = RequestUser.require(request).id();
        jdbc.sql("UPDATE seckill_orders SET status=2,cancel_time=NOW() " +
                        "WHERE order_no=:orderNo AND user_id=:userId AND status=0")
                .param("orderNo", orderNo).param("userId", userId).update();
        return ApiResponse.ok("取消成功", null);
    }

    @GetMapping("/api/admin/orders")
    ApiResponse<List<AdminOrderView>> all(HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
        return ApiResponse.ok(jdbc.sql("SELECT o.id,o.order_no,o.user_id," +
                        "COALESCE(u.username,'未知用户') buyer_name,u.email buyer_email,o.item_id,o.amount," +
                        "o.status,o.fulfillment_status,o.created_at,o.receiver_name,o.receiver_phone,o.receiver_province," +
                        "o.receiver_city,o.receiver_district,o.receiver_detail,o.shipping_time " +
                        "FROM seckill_orders o LEFT JOIN seckill_auth.users u ON u.id=o.user_id ORDER BY o.id DESC")
                .query(AdminOrderView.class).list());
    }

    @PostMapping("/api/admin/orders/{orderNo}/ship")
    ApiResponse<Void> ship(@PathVariable String orderNo, HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
        int changed = jdbc.sql("UPDATE seckill_orders SET fulfillment_status=2,shipping_time=NOW() " +
                        "WHERE order_no=:orderNo AND status=1 AND fulfillment_status=1 " +
                        "AND receiver_name IS NOT NULL AND receiver_phone IS NOT NULL AND receiver_detail IS NOT NULL")
                .param("orderNo", orderNo).update();
        if (changed == 0) throw new IllegalStateException("订单未支付、地址未填写或订单已发货");
        return ApiResponse.ok("发货成功", null);
    }

    private record AddressRow(Long id, String receiverName, String receiverPhone, String province,
                              String city, String district, String detail) {}
}
