package com.aaa.aihealthcare.aspect;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.service.SysOperationLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationLogAspectTests {

    @Mock
    private SysOperationLogService operationLogService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private OperationLog operationLog;

    @InjectMocks
    private OperationLogAspect aspect;

    @BeforeEach
    void setUpRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setRemoteAddr("10.0.0.1");
        request.setAttribute("loginUsername", "admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(operationLog.module()).thenReturn("用户管理");
        when(operationLog.action()).thenReturn("新增用户");
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void successfulResultIsRecordedAsSuccess() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Result.ok("ok"));

        aspect.around(joinPoint, operationLog);

        verifyRecorded(1);
    }

    @Test
    void resultErrorIsRecordedAsFailure() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Result.error("failed"));

        aspect.around(joinPoint, operationLog);

        verifyRecorded(0);
    }

    @Test
    void responseEntityUsesWrappedResultCode() throws Throwable {
        when(joinPoint.proceed()).thenReturn(ResponseEntity.ok(Result.error("failed")));

        aspect.around(joinPoint, operationLog);

        verifyRecorded(0);
    }

    @Test
    void exceptionIsRecordedAsFailureAndRethrown() throws Throwable {
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> aspect.around(joinPoint, operationLog));

        verifyRecorded(0);
    }

    @Test
    void loggingFailureDoesNotReplaceBusinessResult() throws Throwable {
        Result businessResult = Result.ok("ok");
        when(joinPoint.proceed()).thenReturn(businessResult);
        when(operationLogService.record(eq("admin"), eq("用户管理"), eq("新增用户"),
                eq("POST"), eq("/api/test"), eq("10.0.0.1"), anyInt(), eq(1)))
                .thenThrow(new IllegalStateException("log database unavailable"));

        assertSame(businessResult, aspect.around(joinPoint, operationLog));
    }

    private void verifyRecorded(int status) {
        verify(operationLogService).record(eq("admin"), eq("用户管理"), eq("新增用户"),
                eq("POST"), eq("/api/test"), eq("10.0.0.1"), anyInt(), eq(status));
    }
}
