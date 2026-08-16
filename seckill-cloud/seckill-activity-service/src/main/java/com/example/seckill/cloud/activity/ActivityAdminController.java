package com.example.seckill.cloud.activity;

import com.example.seckill.cloud.common.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@RestController
public class ActivityAdminController {
    private final JdbcClient jdbc;
    private final SeckillEngine engine;
    private final ProductClient products;
    private final StringRedisTemplate redis;
    private final String internalToken;

    public ActivityAdminController(JdbcClient jdbc, SeckillEngine engine, ProductClient products,
                                   StringRedisTemplate redis,
                                   @Value("${security.internal-token}") String internalToken) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.products = products;
        this.redis = redis;
        this.internalToken = internalToken;
    }

    public record Activity(Long id, String name, String description, LocalDateTime previewTime,
                           LocalDateTime startTime, LocalDateTime endTime, Integer status) {}
    public record ItemConfig(@NotNull Long productId, @NotNull @DecimalMin("0.01") BigDecimal seckillPrice,
                             @NotNull @Min(0) Integer stock, @Min(1) Integer limitPerUser) {}
    public record SaveActivity(@NotBlank String name, String description,
                               @NotNull LocalDateTime previewTime, @NotNull LocalDateTime startTime,
                               @NotNull LocalDateTime endTime, List<@Valid ItemConfig> items) {}
    public record SaveItem(@NotNull Long activityId, @NotNull Long productId, String productName,
                           String productTitle, String productImages, BigDecimal originalPrice,
                           @NotNull @DecimalMin("0.01") BigDecimal seckillPrice,
                           @NotNull @Min(0) Integer stock, @Min(1) Integer limitPerUser) {}

    @GetMapping("/api/admin/activities")
    ApiResponse<List<Activity>> list(HttpServletRequest request) {
        admin(request);
        return ApiResponse.ok(jdbc.sql("SELECT id,name,description,preview_time,start_time,end_time," +
                        "CASE WHEN NOW()<preview_time THEN 0 WHEN NOW()<start_time THEN 1 " +
                        "WHEN NOW()<end_time THEN 2 ELSE 3 END status FROM seckill_activities ORDER BY id DESC")
                .query(Activity.class).list());
    }

    @PostMapping("/api/admin/activities")
    @Transactional
    ApiResponse<Void> create(@Valid @RequestBody SaveActivity activity, HttpServletRequest request) {
        admin(request);
        validateTimes(activity);
        jdbc.sql("INSERT INTO seckill_activities(name,description,preview_time,start_time,end_time,status) VALUES(:n,:d,:p,:s,:e,0)")
                .param("n", activity.name()).param("d", activity.description()).param("p", activity.previewTime())
                .param("s", activity.startTime()).param("e", activity.endTime()).update();
        Long activityId = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        if (activity.items() != null) for (ItemConfig item : activity.items()) insertSnapshot(activityId, item);
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/admin/activities/{id}")
    @Transactional
    ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody SaveActivity activity,
                             HttpServletRequest request) {
        admin(request);
        validateTimes(activity);
        int changed = jdbc.sql("UPDATE seckill_activities SET name=:n,description=:d,preview_time=:p,start_time=:s,end_time=:e WHERE id=:id")
                .param("n", activity.name()).param("d", activity.description()).param("p", activity.previewTime())
                .param("s", activity.startTime()).param("e", activity.endTime()).param("id", id).update();
        if (changed == 0) throw new IllegalArgumentException("活动不存在");
        if (activity.items() != null) {
            engine.itemsForActivity(id).forEach(this::clearItemCache);
            jdbc.sql("DELETE FROM seckill_items WHERE activity_id=:id").param("id", id).update();
            for (ItemConfig item : activity.items()) insertSnapshot(id, item);
        }
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/admin/activities/{id}")
    @Transactional
    ApiResponse<Void> deleteActivity(@PathVariable Long id, HttpServletRequest request) {
        admin(request);
        engine.itemsForActivity(id).forEach(this::clearItemCache);
        jdbc.sql("DELETE FROM seckill_items WHERE activity_id=:id").param("id", id).update();
        jdbc.sql("DELETE FROM seckill_activities WHERE id=:id").param("id", id).update();
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/admin/items/{activityId}")
    ApiResponse<List<SeckillEngine.Item>> items(@PathVariable Long activityId, HttpServletRequest request) {
        admin(request);
        return ApiResponse.ok(engine.itemsForActivity(activityId));
    }

    @PostMapping("/api/admin/items")
    ApiResponse<Void> createItem(@Valid @RequestBody SaveItem item, HttpServletRequest request) {
        admin(request);
        if (item.productName() == null) {
            insertSnapshot(item.activityId(), new ItemConfig(item.productId(), item.seckillPrice(), item.stock(), item.limitPerUser()));
        } else {
            insert(item);
        }
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/admin/items/{id}")
    ApiResponse<Void> updateItem(@PathVariable Long id, @Valid @RequestBody SaveItem item,
                                 HttpServletRequest request) {
        admin(request);
        int changed = jdbc.sql("UPDATE seckill_items SET seckill_price=:p,stock=:s,limit_per_user=:l WHERE id=:id")
                .param("p", item.seckillPrice()).param("s", item.stock())
                .param("l", item.limitPerUser() == null ? 1 : item.limitPerUser()).param("id", id).update();
        if (changed == 0) throw new IllegalArgumentException("秒杀商品不存在");
        redis.delete("seckill:stock:" + id);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/admin/items/{id}")
    ApiResponse<Void> deleteItem(@PathVariable Long id, HttpServletRequest request) {
        admin(request);
        jdbc.sql("DELETE FROM seckill_items WHERE id=:id").param("id", id).update();
        redis.delete("seckill:stock:" + id);
        redis.delete("seckill:users:" + id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/admin/stock/{itemId}")
    ApiResponse<Void> warmItem(@PathVariable Long itemId, HttpServletRequest request) {
        admin(request);
        engine.forceWarm(engine.itemById(itemId));
        return ApiResponse.ok(null);
    }

    /** 前端传入的是活动 ID，因此一次预热该活动下的全部秒杀项。 */
    @PostMapping({"/api/admin/activities/warmup/{activityId}", "/api/seckill/admin/warmup/{activityId}"})
    ApiResponse<Void> warmActivity(@PathVariable Long activityId, HttpServletRequest request) {
        admin(request);
        List<SeckillEngine.Item> items = engine.itemsForActivity(activityId);
        if (items.isEmpty()) throw new IllegalArgumentException("活动中没有秒杀商品");
        items.forEach(engine::forceWarm);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/admin/stock/{itemId}")
    ApiResponse<Integer> stock(@PathVariable Long itemId, HttpServletRequest request) {
        admin(request);
        String value = redis.opsForValue().get("seckill:stock:" + itemId);
        return ApiResponse.ok(value == null ? -1 : Integer.valueOf(value));
    }

    private void insertSnapshot(Long activityId, ItemConfig item) {
        ProductClient.ProductSnapshot product = products.one(item.productId(), internalToken).data();
        if (product == null) throw new IllegalArgumentException("商品不存在");
        insert(new SaveItem(activityId, product.id(), product.name(), product.title(), product.images(),
                product.price(), item.seckillPrice(), item.stock(), item.limitPerUser()));
    }

    private void insert(SaveItem item) {
        jdbc.sql("INSERT INTO seckill_items(activity_id,product_id,product_name,product_title,product_images,original_price,seckill_price,stock,limit_per_user,status) VALUES(:a,:p,:n,:t,:img,:op,:sp,:s,:l,1)")
                .param("a", item.activityId()).param("p", item.productId()).param("n", item.productName())
                .param("t", item.productTitle()).param("img", item.productImages()).param("op", item.originalPrice())
                .param("sp", item.seckillPrice()).param("s", item.stock())
                .param("l", item.limitPerUser() == null ? 1 : item.limitPerUser()).update();
    }

    private void validateTimes(SaveActivity activity) {
        if (activity.previewTime().isAfter(activity.startTime()) || !activity.startTime().isBefore(activity.endTime()))
            throw new IllegalArgumentException("时间必须满足：预热时间 ≤ 开始时间 < 结束时间");
    }

    private void clearItemCache(SeckillEngine.Item item) {
        redis.delete("seckill:stock:" + item.id());
        redis.delete("seckill:users:" + item.id());
    }

    private void admin(HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
    }
}
