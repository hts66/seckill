package com.example.seckill.controller;

import com.example.seckill.dto.CreateActivityRequest;
import com.example.seckill.dto.Response;
import com.example.seckill.entity.SeckillActivity;
import com.example.seckill.entity.SeckillItem;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.mapper.SeckillItemMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.service.ActivityService;
import com.example.seckill.service.SeckillService;
import com.example.seckill.redis.RedisStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 后台管理接口
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ActivityService activityService;
    private final SeckillService seckillService;
    private final SeckillOrderMapper orderMapper;
    private final SeckillItemMapper itemMapper;
    private final RedisStockService redisStockService;

    // ==================== 活动管理 ====================

    /**
     * 创建秒杀活动（含秒杀项批量关联）
     */
    @PostMapping("/activities")
    public Response<SeckillActivity> createActivity(@RequestBody CreateActivityRequest request) {
        try {
            SeckillActivity activity = activityService.createActivity(request);
            return Response.success("创建成功", activity);
        } catch (Exception e) {
            return Response.error(e.getMessage());
        }
    }

    @GetMapping("/activities")
    public Response<List<SeckillActivity>> getActivities() {
        return Response.success(activityService.list());
    }

    @PutMapping("/activities/{id}")
    public Response<SeckillActivity> updateActivity(
            @PathVariable Long id, @RequestBody CreateActivityRequest request) {
        try {
            SeckillActivity activity = activityService.updateActivity(id, request);
            return Response.success(activity);
        } catch (Exception e) {
            return Response.error(e.getMessage());
        }
    }

    @DeleteMapping("/activities/{id}")
    public Response<String> deleteActivity(@PathVariable Long id) {
        try {
            activityService.removeById(id);
            return Response.success("删除成功");
        } catch (Exception e) {
            return Response.error(e.getMessage());
        }
    }

    // ==================== 秒杀商品管理 ====================

    @PostMapping("/items")
    public Response<SeckillItem> createItem(@RequestBody SeckillItem item) {
        itemMapper.insert(item);
        return Response.success(item);
    }

    @GetMapping("/items/{activityId}")
    public Response<List<SeckillItem>> getItems(@PathVariable Long activityId) {
        return Response.success(activityService.getItemsByActivity(activityId));
    }

    @PutMapping("/items/{id}")
    public Response<SeckillItem> updateItem(@PathVariable Long id, @RequestBody SeckillItem item) {
        item.setId(id);
        itemMapper.updateById(item);
        return Response.success(item);
    }

    @DeleteMapping("/items/{id}")
    public Response<String> deleteItem(@PathVariable Long id) {
        itemMapper.deleteById(id);
        return Response.success("删除成功");
    }

    // ==================== 活动控制 ====================

    @PostMapping("/warmup/{activityId}")
    public Response<String> warmUp(@PathVariable Long activityId) {
        seckillService.warmUpActivity(activityId);
        return Response.success("活动预热完成，库存已加载到 Redis");
    }

    @GetMapping("/stock/{itemId}")
    public Response<Integer> getRedisStock(@PathVariable Long itemId) {
        return Response.success(redisStockService.getStock(itemId));
    }

    // ==================== 订单管理 ====================

    @GetMapping("/orders")
    public Response<List<SeckillOrder>> getOrders() {
        return Response.success(orderMapper.selectList(null));
    }
}
