package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckill.dto.CreateActivityRequest;
import com.example.seckill.entity.SeckillActivity;
import com.example.seckill.entity.SeckillItem;
import com.example.seckill.mapper.SeckillActivityMapper;
import com.example.seckill.mapper.SeckillItemMapper;
import com.example.seckill.service.ActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl extends ServiceImpl<SeckillActivityMapper, SeckillActivity>
        implements ActivityService {

    private final SeckillItemMapper itemMapper;

    @Override
    public List<SeckillItem> getItemsByActivity(Long activityId) {
        return itemMapper.selectItemsByActivity(activityId);
    }

    @Override
    public SeckillItem getItemDetail(Long itemId) {
        return itemMapper.selectItemDetail(itemId);
    }

    /**
     * 获取当前进行中的活动（startTime <= now <= endTime，已开抢）
     * 纯时间驱动，不依赖 status 字段
     */
    @Override
    public SeckillActivity getCurrentActivity() {
        LocalDateTime now = LocalDateTime.now();
        return this.getOne(new LambdaQueryWrapper<SeckillActivity>()
                .le(SeckillActivity::getStartTime, now)
                .ge(SeckillActivity::getEndTime, now)
                .ne(SeckillActivity::getStatus, 3)   // 排除手动结束的
                .last("LIMIT 1")
        );
    }

    /**
     * 获取当前预热中的活动（previewTime <= now < startTime，已展示但未开抢）
     * 纯时间驱动，不依赖 status 字段
     */
    @Override
    public SeckillActivity getPreviewActivity() {
        LocalDateTime now = LocalDateTime.now();
        return this.getOne(new LambdaQueryWrapper<SeckillActivity>()
                .le(SeckillActivity::getPreviewTime, now)
                .gt(SeckillActivity::getStartTime, now)
                .ne(SeckillActivity::getStatus, 3)   // 排除手动结束的
                .last("LIMIT 1")
        );
    }

    @Override
    @Transactional
    public SeckillActivity createActivity(CreateActivityRequest request) {
        SeckillActivity activity = new SeckillActivity();
        activity.setName(request.getName());
        activity.setDescription(request.getDescription());
        activity.setPreviewTime(request.getPreviewTime());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());
        activity.setStatus(0); // 未开始

        save(activity);
        log.info("活动创建成功 id={} name={}", activity.getId(), activity.getName());

        // 批量创建秒杀项
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (CreateActivityRequest.ItemConfig itemConfig : request.getItems()) {
                SeckillItem item = new SeckillItem();
                item.setActivityId(activity.getId());
                item.setProductId(itemConfig.getProductId());
                item.setSeckillPrice(itemConfig.getSeckillPrice());
                item.setStock(itemConfig.getStock());
                item.setLimitPerUser(itemConfig.getLimitPerUser() != null ? itemConfig.getLimitPerUser() : 1);
                item.setStatus(1);
                itemMapper.insert(item);
            }
            log.info("秒杀项批量创建完成 activityId={} 数量={}", activity.getId(), request.getItems().size());
        }

        return activity;
    }

    @Override
    @Transactional
    public SeckillActivity updateActivity(Long id, CreateActivityRequest request) {
        SeckillActivity activity = getById(id);
        if (activity == null) {
            throw new RuntimeException("活动不存在");
        }

        activity.setName(request.getName());
        activity.setDescription(request.getDescription());
        activity.setPreviewTime(request.getPreviewTime());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());

        updateById(activity);
        log.info("活动更新成功 id={}", id);
        return activity;
    }
}
