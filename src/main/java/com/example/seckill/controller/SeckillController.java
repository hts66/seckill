package com.example.seckill.controller;

import com.example.seckill.annotation.AccessLimit;
import com.example.seckill.dto.Response;
import com.example.seckill.dto.SeckillRequest;
import com.example.seckill.dto.SeckillResult;
import com.example.seckill.entity.SeckillItem;
import com.example.seckill.service.ActivityService;
import com.example.seckill.service.SeckillService;
import com.example.seckill.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 秒杀核心控制器
 *
 * 核心接口调用链：
 *   客户端 → GET  /api/seckill/items          查看秒杀商品列表
 *   客户端 → GET  /api/seckill/path/{itemId}   获取动态秒杀路径（活动开始时）
 *   客户端 → POST /api/seckill/execute/{pathKey} 执行秒杀（带真实路径）
 *   客户端 → GET  /api/seckill/result/{itemId}  轮询秒杀结果
 */
@Slf4j
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final ActivityService activityService;
    private final JwtUtil jwtUtil;

    /**
     * 查询当前进行中的秒杀商品列表（已开抢）
     */
    @GetMapping("/items")
    public Response<List<SeckillItem>> getSeckillItems() {
        try {
            var activity = activityService.getCurrentActivity();
            if (activity == null) {
                return Response.error(404, "当前没有进行中的秒杀活动");
            }
            List<SeckillItem> items = activityService.getItemsByActivity(activity.getId());
            return Response.success(items);
        } catch (Exception e) {
            log.error("获取秒杀商品失败", e);
            return Response.error(e.getMessage());
        }
    }

    /**
     * 查询预热中的秒杀商品列表（已展示倒计时但未开抢）
     * 返回商品信息 + 开抢倒计时
     */
    @GetMapping("/upcoming")
    public Response<List<SeckillItem>> getUpcomingItems() {
        try {
            var activity = activityService.getPreviewActivity();
            if (activity == null) {
                return Response.error(404, "当前没有预热中的秒杀活动");
            }
            List<SeckillItem> items = activityService.getItemsByActivity(activity.getId());
            return Response.success(items);
        } catch (Exception e) {
            log.error("获取预热商品失败", e);
            return Response.error(e.getMessage());
        }
    }

    /**
     * 获取动态秒杀路径（隐藏真实接口地址）
     * 只有活动进行中才能获取，返回一个一次性路径 key
     */
    @GetMapping("/path/{itemId}")
    public Response<Map<String, String>> getSeckillPath(
            @RequestHeader("Authorization") String auth,
            @PathVariable Long itemId) {
        try {
            Long userId = jwtUtil.validateAndGetUserId(extractToken(auth));
            if (userId == null) return Response.error(401, "未登录");

            String pathKey = seckillService.getSeckillPath(userId, itemId);
            return Response.success(Map.of("pathKey", pathKey));
        } catch (Exception e) {
            log.error("获取秒杀路径失败", e);
            return Response.error(e.getMessage());
        }
    }

    /**
     * 执行秒杀（核心接口）
     * URL 包含动态密钥，防止提前暴露
     *
     * 请求示例：POST /api/seckill/execute/a1b2c3d4e5f6
     * Body: { "itemId": 1 }
     */
    @AccessLimit(seconds = 5, maxCount = 10)
    @PostMapping("/execute/{pathKey}")
    public Response<SeckillResult> executeSeckill(
            @RequestHeader("Authorization") String auth,
            @PathVariable String pathKey,
            @RequestBody SeckillRequest request) {
        try {
            Long userId = jwtUtil.validateAndGetUserId(extractToken(auth));
            if (userId == null) return Response.error(401, "未登录");

            SeckillResult result = seckillService.executeSeckill(userId, request.getItemId(), pathKey);

            // 所有业务结果统一返回 200，由前端根据 result.status 判断
            // status: 0-排队中 1-抢到 2-已抢完 3-已买过 4-活动未开始 5-活动已结束
            String msg = switch (result.getStatus()) {
                case 0 -> "排队中，请查询结果";
                case 1 -> "恭喜抢到！";
                case 2 -> "商品已抢完";
                case 3 -> "您已购买过该商品";
                case 5 -> "活动未开始或已结束";
                default -> "秒杀失败";
            };
            return Response.success(msg, result);
        } catch (Exception e) {
            log.error("秒杀执行失败", e);
            return Response.error(500, "秒杀失败: " + e.getMessage());
        }
    }

    /**
     * 查询秒杀结果（前端轮询用，每 500ms 调一次）
     */
    @GetMapping("/result/{itemId}")
    public Response<SeckillResult> getSeckillResult(
            @RequestHeader("Authorization") String auth,
            @PathVariable Long itemId) {
        try {
            Long userId = jwtUtil.validateAndGetUserId(extractToken(auth));
            if (userId == null) return Response.error(401, "未登录");

            SeckillResult result = seckillService.getSeckillResult(userId, itemId);
            return Response.success(result);
        } catch (Exception e) {
            log.error("查询秒杀结果失败", e);
            return Response.error(e.getMessage());
        }
    }

    /**
     * 活动预热（管理接口，将库存加载到 Redis）
     */
    @PostMapping("/admin/warmup/{activityId}")
    public Response<String> warmUp(@PathVariable Long activityId) {
        try {
            seckillService.warmUpActivity(activityId);
            return Response.success("预热成功");
        } catch (Exception e) {
            log.error("活动预热失败", e);
            return Response.error(e.getMessage());
        }
    }

    private String extractToken(String auth) {
        return (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
    }
}
