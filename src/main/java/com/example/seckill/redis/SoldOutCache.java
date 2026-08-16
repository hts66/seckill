package com.example.seckill.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM 本地内存售罄标记
 * 商品售罄后写入标记，后续请求直接返回，不走Redis
 * 减少 Redis 压力，提升超高并发下的吞吐量
 */
@Slf4j
@Component
public class SoldOutCache {

    /**
     * itemId → true 表示已售罄
     */
    private final Map<Long, Boolean> soldOutMap = new ConcurrentHashMap<>();

    /**
     * 标记售罄
     */
    public void markSoldOut(Long itemId) {
        soldOutMap.put(itemId, true);
        log.info("JVM本地标记售罄 itemId={}", itemId);
    }

    /**
     * 是否已售罄
     */
    public boolean isSoldOut(Long itemId) {
        return soldOutMap.getOrDefault(itemId, false);
    }

    /**
     * 清除售罄标记（活动重新开始或补货时调用）
     */
    public void clearSoldOut(Long itemId) {
        soldOutMap.remove(itemId);
        log.info("清除售罄标记 itemId={}", itemId);
    }

    /**
     * 获取当前售罄的商品数量
     */
    public int size() {
        return soldOutMap.size();
    }
}
