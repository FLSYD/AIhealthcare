package com.aaa.aihealthcare.common;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class UploadExceptionHandler {
    // 大文件可能在匹配 Controller 之前被拒绝，统一返回项目已有的业务结构。
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result uploadTooLarge(MaxUploadSizeExceededException exception) {
        return Result.error("头像不能超过2MB");
    }
}
