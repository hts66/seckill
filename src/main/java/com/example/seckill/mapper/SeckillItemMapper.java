package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.SeckillItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SeckillItemMapper extends BaseMapper<SeckillItem> {

    /**
     * 查询活动下所有秒杀商品（含商品信息 + 活动信息）
     */
    @Select("""
        SELECT si.*, p.name AS product_name, p.title AS product_title,
               p.images AS product_images,
               p.price AS original_price, sa.name AS activity_name,
               sa.preview_time AS activity_preview_time,
               sa.start_time AS activity_start_time, sa.end_time AS activity_end_time
        FROM seckill_items si
        JOIN products p ON si.product_id = p.id
        JOIN seckill_activities sa ON si.activity_id = sa.id
        WHERE si.activity_id = #{activityId} AND si.status = 1
        ORDER BY si.id
    """)
    List<SeckillItem> selectItemsByActivity(Long activityId);

    /**
     * 查询单个秒杀商品详情（含商品信息）
     */
    @Select("""
        SELECT si.*, p.name AS product_name, p.title AS product_title,
               p.images AS product_images,
               p.price AS original_price, sa.name AS activity_name,
               sa.preview_time AS activity_preview_time,
               sa.start_time AS activity_start_time, sa.end_time AS activity_end_time
        FROM seckill_items si
        JOIN products p ON si.product_id = p.id
        JOIN seckill_activities sa ON si.activity_id = sa.id
        WHERE si.id = #{id}
    """)
    SeckillItem selectItemDetail(Long id);
}
