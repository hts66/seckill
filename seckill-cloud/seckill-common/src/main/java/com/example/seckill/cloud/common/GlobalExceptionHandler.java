package com.example.seckill.cloud.common;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> validation(MethodArgumentNotValidException e) {
        var error = e.getBindingResult().getFieldError();
        return ApiResponse.error(400, error == null ? "请求参数不正确" : error.getDefaultMessage());
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, HttpMessageNotReadableException.class})
    public ApiResponse<Void> business(Exception e) { return ApiResponse.error(400, e.getMessage()); }
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> unknown(Exception e) { return ApiResponse.error(500, "服务器暂时不可用"); }
}
