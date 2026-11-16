package com.aaa.aihealthcare.aspect;

import com.aaa.aihealthcare.common.ClientIp;
import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.service.SysOperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class OperationLogAspect {

    @Autowired
    private SysOperationLogService sysOperationLogService;

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        long start = System.currentTimeMillis();
        boolean success = false;
        try {
            Object result = joinPoint.proceed();
            success = isSuccessful(result);
            return result;
        } finally {
            writeLog(operationLog, start, success);
        }
    }

    private boolean isSuccessful(Object result) {
        if (result instanceof Result response) {
            return response.getCode() == 200;
        }
        if (result instanceof ResponseEntity<?> response) {
            return response.getStatusCode().is2xxSuccessful()
                    && response.getBody() instanceof Result body
                    && body.getCode() == 200;
        }
        return false;
    }

    // 日志写入失败不能影响业务结果，因此单独吞掉异常。
    private void writeLog(OperationLog operationLog, long start, boolean success) {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest request = attributes == null ? null : attributes.getRequest();
            String username = request == null ? null : (String) request.getAttribute("loginUsername");
            String method = request == null ? null : request.getMethod();
            String url = request == null ? null : request.getRequestURI();
            String ip = request == null ? null : ClientIp.resolve(request);
            int costMs = (int) (System.currentTimeMillis() - start);

            sysOperationLogService.record(username, operationLog.module(), operationLog.action(),
                    method, url, ip, costMs, success ? 1 : 0);
        } catch (Exception ignored) {
        }
    }
}
