package com.aaa.aihealthcare.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.aaa.aihealthcare.controller")
public class RoleAccessExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result> denied(ResponseStatusException exception) {
        int code = exception.getStatusCode().value();
        return ResponseEntity.status(code).body(new Result(code,
                exception.getReason() == null ? "当前请求不可执行" : exception.getReason(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result invalidRequest(IllegalArgumentException exception) {
        return Result.error(exception.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, UnsupportedOperationException.class})
    public ResponseEntity<Result> businessState(RuntimeException exception) {
        // 业务校验消息保留；底层数据库或未知异常不在此处直接向用户输出。
        return ResponseEntity.status(409).body(new Result(409,
                exception.getMessage() == null ? "当前状态无法执行此操作，请刷新后重试" : exception.getMessage(), null));
    }
}
