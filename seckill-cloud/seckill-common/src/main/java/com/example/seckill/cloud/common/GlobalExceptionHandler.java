package com.example.seckill.cloud.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> validation(MethodArgumentNotValidException e) {
        var error = e.getBindingResult().getFieldError();
        return ApiResponse.error(400, error == null ? "请求参数不正确" : error.getDefaultMessage());
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, HttpMessageNotReadableException.class})
    public ApiResponse<Void> business(Exception e) { return ApiResponse.error(400, e.getMessage()); }
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> unknown(Exception e) {
        log.error("unhandled server error", e);
        return ApiResponse.error(500, "服务器暂时不可用");
    }
}
