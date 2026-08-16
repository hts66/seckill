package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    @Select("""
        SELECT so.*, p.name AS product_name, si.seckill_price
        FROM seckill_orders so
        JOIN seckill_items si ON so.item_id = si.id
        JOIN products p ON si.product_id = p.id
        WHERE so.user_id = #{userId}
        ORDER BY so.created_at DESC
    """)
    List<SeckillOrder> selectOrdersByUser(Long userId);

    @Update("UPDATE seckill_items SET stock = stock - 1 WHERE id = #{itemId} AND stock > 0")
    int deductStock(Long itemId);
}
