package com.example.seckill.cloud.activity;

import com.example.seckill.cloud.api.OrderResultView;
import com.example.seckill.cloud.common.ApiResponse;
import com.example.seckill.cloud.common.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class SeckillController {
    private final SeckillEngine engine;

    public SeckillController(SeckillEngine e) { engine = e; }

    @GetMapping("/api/seckill/items")
    ApiResponse<List<SeckillEngine.Item>> items() {
        return ApiResponse.ok(engine.visible(false));
    }

    @GetMapping("/api/seckill/upcoming")
    ApiResponse<List<SeckillEngine.Item>> upcoming() {
        return ApiResponse.ok(engine.visible(true));
    }

    /** 商品详情：公开访问，点击商品先进详情页查看，再决定是否抢购。 */
    @GetMapping("/api/seckill/items/{itemId}")
    ApiResponse<SeckillEngine.Item> detail(@PathVariable Long itemId) {
        return ApiResponse.ok(engine.itemById(itemId));
    }

    @GetMapping("/api/seckill/path/{itemId}")
    ApiResponse<Map<String, String>> path(@PathVariable Long itemId, HttpServletRequest r) {
        RequestUser u = RequestUser.require(r);
        u.requireCustomer();
        return ApiResponse.ok(Map.of("path", engine.path(u.id(), itemId)));
    }

    @PostMapping("/api/seckill/execute/{path}")
    ApiResponse<OrderResultView> execute(@PathVariable String path, @RequestBody Map<String, Long> b, HttpServletRequest r) {
        RequestUser u = RequestUser.require(r);
        u.requireCustomer();
        return ApiResponse.ok(engine.execute(u.id(), b.get("itemId"), b.get("addressId"), path));
    }

    @GetMapping("/api/seckill/result/{itemId}")
    ApiResponse<OrderResultView> result(@PathVariable Long itemId, HttpServletRequest r) {
        return ApiResponse.ok(engine.result(RequestUser.require(r).id(), itemId));
    }
}
