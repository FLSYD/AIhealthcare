package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AvatarStorage;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.dao.SysUserDao;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 锁序与文件清理遵循事务完成状态，不能把任意提交异常都当成已回滚。 */
class AvatarWriteExecutorTests {
    private final SysUserDao accounts = mock(SysUserDao.class);
    private final RecordingManager manager = new RecordingManager();
    private final AvatarWriteExecutor executor = new AvatarWriteExecutor(accounts, manager);

    @Test
    void adminSqlLockIsTakenBeforeAvatarMonitorAndCommitFollowsAction() {
        AtomicBoolean monitorHeld = new AtomicBoolean();
        doAnswer(invocation -> {
            assertTrue(manager.begun);
            assertFalse(Thread.holdsLock(AvatarStorage.LOCK), "取管理员SQL锁时不得已持有头像JVM锁");
            return List.of(1L);
        }).when(accounts).lockActiveAdminIds();
        Result result = executor.write(() -> {
            monitorHeld.set(Thread.holdsLock(AvatarStorage.LOCK));
            assertFalse(manager.committed);
            return Result.ok("x");
        }, () -> fail("成功路径不得清理"), "失败");
        assertEquals(200, result.getCode());
        assertTrue(monitorHeld.get());
        assertTrue(manager.committed);
    }

    @Test
    void unknownCommitRetainsFileAndReturnsError() {
        manager.failCommit = true;
        AtomicBoolean cleaned = new AtomicBoolean();
        Result result = executor.write(() -> Result.ok("x"), () -> cleaned.set(true), "头像保存失败，请稍后重试");
        assertEquals(500, result.getCode());
        assertEquals("头像保存失败，请稍后重试", result.getMsg());
        assertFalse(cleaned.get(), "UNKNOWN 不能证明回滚，必须保留文件");
        assertFalse(manager.rolledBack);
    }

    @Test
    void nonSuccessResultRollsBackAndIsReturned() {
        AtomicBoolean cleaned = new AtomicBoolean();
        Result result = executor.write(() -> Result.error("业务拒绝"), () -> cleaned.set(true), "失败");
        assertEquals("业务拒绝", result.getMsg());
        assertTrue(manager.rolledBack);
        assertTrue(cleaned.get());
    }

    @Test void actionExceptionRollsBackBeforeCleaningFile() {
        AtomicBoolean cleaned = new AtomicBoolean();
        Result result = executor.write(() -> { throw new IllegalStateException("synthetic-action-failure"); },
                () -> { assertTrue(manager.rolledBack); cleaned.set(true); }, "失败");
        assertEquals(500, result.getCode());
        assertTrue(cleaned.get());
    }

    @Test void absentCompletionNotificationRetainsFile() {
        PlatformTransactionManager withoutSynchronization = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(withoutSynchronization.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        doThrow(new TransactionSystemException("synthetic-no-completion")).when(withoutSynchronization).commit(status);
        AtomicBoolean cleaned = new AtomicBoolean();
        Result result = new AvatarWriteExecutor(accounts, withoutSynchronization).write(
                () -> Result.ok("x"), () -> cleaned.set(true), "失败");
        assertEquals(500, result.getCode());
        assertFalse(cleaned.get());
    }

    @Test void exceptionAfterCommittedNotificationRetainsFile() {
        PlatformTransactionManager afterCommitFailure = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                throw new TransactionSystemException("synthetic-after-commit");
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        AtomicBoolean cleaned = new AtomicBoolean();
        Result result = new AvatarWriteExecutor(accounts, afterCommitFailure).write(
                () -> Result.ok("x"), () -> cleaned.set(true), "失败");
        assertEquals(500, result.getCode());
        assertTrue(manager.committed);
        assertFalse(cleaned.get(), "提交后的异常不得破坏已提交引用");
    }

    private static class RecordingManager extends AbstractPlatformTransactionManager {
        boolean begun, committed, rolledBack, failCommit;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) { begun = true; }
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new TransactionSystemException("synthetic-unknown-commit");
            committed = true;
        }
        protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
