package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.SysLoginLogDao;
import com.aaa.aihealthcare.entity.SysLoginLog;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysLoginLogServiceImplTests {

    @Mock
    private SysLoginLogDao loginLogDao;

    @InjectMocks
    private SysLoginLogServiceImpl service;

    @AfterEach
    void clearPage() {
        PageHelper.clearPage();
    }

    @Test
    void saveNormalizesUsernameAndDefaultsStatus() {
        SysLoginLog loginLog = validLog();
        loginLog.setUsername("  admin  ");
        loginLog.setStatus(null);
        when(loginLogDao.insert(loginLog)).thenReturn(1);

        assertTrue(service.save(loginLog));

        assertEquals("admin", loginLog.getUsername());
        assertEquals(1, loginLog.getStatus());
    }

    @Test
    void saveRejectsInvalidStatusAndDatabaseLengths() {
        SysLoginLog invalidStatus = validLog();
        invalidStatus.setStatus(2);
        assertThrows(IllegalArgumentException.class, () -> service.save(invalidStatus));

        SysLoginLog longUsername = validLog();
        longUsername.setUsername("a".repeat(51));
        assertThrows(IllegalArgumentException.class, () -> service.save(longUsername));

        SysLoginLog longIp = validLog();
        longIp.setIp("1".repeat(51));
        assertThrows(IllegalArgumentException.class, () -> service.save(longIp));

        SysLoginLog longMessage = validLog();
        longMessage.setMessage("m".repeat(201));
        assertThrows(IllegalArgumentException.class, () -> service.save(longMessage));

        assertThrows(IllegalArgumentException.class, () -> service.save(null));
        verifyNoInteractions(loginLogDao);
    }

    @Test
    void saveAcceptsExactDatabaseLengths() {
        SysLoginLog loginLog = validLog();
        loginLog.setUsername("u".repeat(50));
        loginLog.setIp("i".repeat(50));
        loginLog.setMessage("m".repeat(200));
        when(loginLogDao.insert(loginLog)).thenReturn(1);

        assertTrue(service.save(loginLog));
    }

    @Test
    void pageClearsPageHelperStateWhenQueryFails() {
        when(loginLogDao.selectList(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> service.page(null, 1, 10));
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void missingIdPreventsAnyLoginLogBatchDelete() {
        when(loginLogDao.selectCount(any())).thenReturn(1L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.deleteBatch(List.of(2L, 3L)));

        assertEquals("部分日志不存在，批量删除已取消", exception.getMessage());
        verify(loginLogDao, never()).deleteBatchIds(any());
    }

    @Test
    void loginLogBatchDeleteDeduplicatesAndSortsIds() {
        when(loginLogDao.selectCount(any())).thenReturn(2L);
        when(loginLogDao.deleteBatchIds(List.of(2L, 3L))).thenReturn(2);

        assertTrue(service.deleteBatch(List.of(3L, 2L, 3L)));

        verify(loginLogDao).deleteBatchIds(List.of(2L, 3L));
    }

    private SysLoginLog validLog() {
        SysLoginLog loginLog = new SysLoginLog();
        loginLog.setUsername("admin");
        loginLog.setIp("127.0.0.1");
        loginLog.setStatus(0);
        loginLog.setMessage("登录失败");
        return loginLog;
    }
}
