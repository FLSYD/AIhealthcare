package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.NotificationDao;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTests {
    @Mock
    private NotificationDao dao;
    @InjectMocks
    private NotificationServiceImpl service;

    @Test
    void cannotMarkAnotherUsersNotificationAndDoesNotIssueWrite() {
        when(dao.existsForUser(9L, 2L)).thenReturn(false);
        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> service.markRead(2L, 9L));
        assertEquals(403, failure.getStatusCode().value());
        verify(dao, never()).markRead(anyLong(), anyLong());
    }

    @Test
    void repeatedMarkReadIsIdempotentAndAlwaysScopedToCurrentUser() {
        when(dao.existsForUser(9L, 2L)).thenReturn(true);
        service.markRead(2L, 9L);
        service.markRead(2L, 9L);
        verify(dao, times(2)).markRead(9L, 2L);
    }

    @Test
    void failedPageQueryDoesNotLeakPagingIntoNextRequest() {
        when(dao.selectByUser(2L, true)).thenThrow(new IllegalStateException("数据库错误"));
        assertThrows(IllegalStateException.class, () -> service.page(2L, true, 1, 10));
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void missingIdentityIsRejectedBeforeQuery() {
        assertThrows(ResponseStatusException.class, () -> service.unreadCount(null));
        verifyNoInteractions(dao);
    }
}
