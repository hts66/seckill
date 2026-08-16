package com.example.seckill.cloud.api;

import java.io.Serializable;

public record OrderResultView(int status, String orderNo, String message) implements Serializable {
    public static OrderResultView pending() { return new OrderResultView(0, null, "排队中"); }
}
