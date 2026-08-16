package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.seckill.dto.CreateActivityRequest;
import com.example.seckill.entity.SeckillActivity;
import com.example.seckill.entity.SeckillItem;

import java.util.List;

public interface ActivityService extends IService<SeckillActivity> {
    List<SeckillItem> getItemsByActivity(Long activityId);
    SeckillItem getItemDetail(Long itemId);

    /** 获取当前进行中的活动（已开抢） */
    SeckillActivity getCurrentActivity();

    /** 获取当前预热中的活动（已展示但未开抢） */
    SeckillActivity getPreviewActivity();

    /** 创建活动（含秒杀项批量关联） */
    SeckillActivity createActivity(CreateActivityRequest request);

    /** 更新活动 */
    SeckillActivity updateActivity(Long id, CreateActivityRequest request);
}
