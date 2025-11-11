package com.example.orderservice.exception;

/**
 * 自定义一个“业务异常”，用于封装所有可预期的、非系统性的失败
 * 继承 RuntimeException 以便能触发 @Transactional 的自动回滚
 */
public class SeckillBusinessException extends RuntimeException {
    public SeckillBusinessException(String message) {
        super(message);
    }
}