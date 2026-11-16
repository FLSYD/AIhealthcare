package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.SysOperationLogDao;
import com.aaa.aihealthcare.entity.SysOperationLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysOperationLogServiceImplTests {

    @Mock
    private SysOperationLogDao operationLogDao;

    @InjectMocks
    private SysOperationLogServiceImpl service;

    @Test
    void missingIdPreventsAnyBatchDelete() {
        when(operationLogDao.selectCount(any())).thenReturn(1L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.deleteBatch(List.of(2L, 3L)));

        assertEquals("部分日志不存在，批量删除已取消", exception.getMessage());
        verify(operationLogDao, never()).deleteBatchIds(any());
    }

    @Test
    void batchDeleteDeduplicatesAndSortsIds() {
        when(operationLogDao.selectCount(any())).thenReturn(2L);
        when(operationLogDao.deleteBatchIds(List.of(2L, 3L))).thenReturn(2);

        assertTrue(service.deleteBatch(List.of(3L, 2L, 3L)));

        verify(operationLogDao).deleteBatchIds(List.of(2L, 3L));
    }

    @Test
    void affectedRowMismatchThrowsToTriggerRollback() {
        when(operationLogDao.selectCount(any())).thenReturn(2L);
        when(operationLogDao.deleteBatchIds(List.of(2L, 3L))).thenReturn(1);

        assertThrows(IllegalArgumentException.class,
                () -> service.deleteBatch(List.of(2L, 3L)));
    }

    @Test
    void batchBoundariesRejectNullEmptyOversizedAndInvalidIds() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteBatch(null));
        assertThrows(IllegalArgumentException.class, () -> service.deleteBatch(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteBatch(Collections.nCopies(101, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteBatch(Arrays.asList(1L, null)));
        assertThrows(IllegalArgumentException.class, () -> service.deleteBatch(List.of(0L)));

        verifyNoInteractions(operationLogDao);
    }

    @Test
    void saveUsesActualActionAndUrlLengths() {
        SysOperationLog exact = validOperationLog();
        exact.setAction("a".repeat(50));
        exact.setUrl("u".repeat(200));
        when(operationLogDao.insert(exact)).thenReturn(1);

        assertTrue(service.save(exact));

        SysOperationLog longAction = validOperationLog();
        longAction.setAction("a".repeat(51));
        assertThrows(IllegalArgumentException.class, () -> service.save(longAction));

        SysOperationLog longUrl = validOperationLog();
        longUrl.setUrl("u".repeat(201));
        assertThrows(IllegalArgumentException.class, () -> service.save(longUrl));
    }

    private SysOperationLog validOperationLog() {
        SysOperationLog operationLog = new SysOperationLog();
        operationLog.setUsername("admin");
        operationLog.setModule("模块");
        operationLog.setAction("操作");
        operationLog.setMethod("POST");
        operationLog.setUrl("/api/test");
        operationLog.setIp("127.0.0.1");
        operationLog.setCostMs(1);
        operationLog.setStatus(1);
        return operationLog;
    }
}
