package com.example.seckill.annotation;

import java.lang.annotation.*;

/**
 * 接口限流注解
 * N秒内最多M次请求，超出返回429
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AccessLimit {

    /** 时间窗口（秒），默认5秒 */
    int seconds() default 5;

    /** 最大请求次数，默认10次 */
    int maxCount() default 10;
}
