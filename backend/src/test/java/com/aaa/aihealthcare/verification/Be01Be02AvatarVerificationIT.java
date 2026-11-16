package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.dao.LoginDao;
import com.aaa.aihealthcare.dao.MiniAuthDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.service.impl.AvatarCleanupTask;
import com.aaa.aihealthcare.service.impl.AvatarWriteExecutor;
import com.aaa.aihealthcare.service.impl.LoginServiceImpl;
import com.aaa.aihealthcare.service.impl.MiniAvatarServiceImpl;
import com.aaa.aihealthcare.vo.MiniAvatarBindRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BE01/BE02 专属库验证。并发用例让主线程事务先持有管理员集合SQL锁（等同回收线程的位置），
 * 再启动头像写入线程，并检查其线程栈已阻塞在管理员集合锁查询（JDBC读等待）后才放行，不依赖固定睡眠。
 */
class Be01Be02AvatarVerificationIT extends MysqlVerificationSupport {
    @TempDir Path avatarDirectory;
    @Autowired LoginDao loginDao;
    @Autowired MiniAuthDao miniAuthDao;
    @Autowired SysUserDao sysUserDao;
    @Autowired StringRedisTemplate redis;
    @Autowired AvatarWriteExecutor avatarWriter;

    private LoginServiceImpl loginService(AvatarWriteExecutor executor) {
        LoginServiceImpl service = new LoginServiceImpl();
        ReflectionTestUtils.setField(service, "loginDao", loginDao);
        ReflectionTestUtils.setField(service, "avatarDirectory", avatarDirectory.toString());
        ReflectionTestUtils.setField(service, "avatarWriter", executor);
        return service;
    }

    private MiniAvatarServiceImpl miniService(LoginServiceImpl login, AvatarWriteExecutor executor) {
        MiniAvatarServiceImpl service = new MiniAvatarServiceImpl(redis, miniAuthDao, login, executor);
        ReflectionTestUtils.setField(service, "avatarDirectory", avatarDirectory.toString());
        return service;
    }

    private AvatarCleanupTask cleanupTask() {
        AvatarCleanupTask task = new AvatarCleanupTask(loginDao, redis, sysUserDao, transactions);
        ReflectionTestUtils.setField(task, "avatarDirectory", avatarDirectory.toString());
        return task;
    }

    private MockMultipartFile png() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("file", "synthetic.png", "image/png", bytes.toByteArray());
    }

    private void awaitRowLockWait(Thread writerThread) throws Exception {
        for (int i = 0; i < 100; i++) {
            StackTraceElement[] stack = writerThread == null ? new StackTraceElement[0] : writerThread.getStackTrace();
            boolean inLockQuery = false, inDriver = false;
            for (StackTraceElement frame : stack) {
                if ("lockActiveAdminIds".equals(frame.getMethodName())) inLockQuery = true;
                if (frame.getClassName().startsWith("com.mysql.cj")) inDriver = true;
            }
            if (inLockQuery && inDriver) return;
            Thread.sleep(50);
        }
        fail("写入线程没有阻塞在管理员集合锁查询上，无法证明交错点");
    }

    private long fileCount() throws Exception {
        try (var stream = Files.list(avatarDirectory)) { return stream.count(); }
    }

    /** 主线程事务先持管理员SQL锁并在其中运行回收（加入同一事务），写入线程此时必须在SQL层等待而不持JVM锁。 */
    private void runAgainstCollection(Callable<Result> writer, Result[] holder) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Future<Result>> pending = new AtomicReference<>();
            AtomicReference<Thread> writerThread = new AtomicReference<>();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                sysUserDao.lockActiveAdminIds();
                Future<Result> future = pool.submit(() -> { writerThread.set(Thread.currentThread()); return writer.call(); });
                pending.set(future);
                try {
                    for (int i = 0; i < 100 && writerThread.get() == null; i++) Thread.sleep(20);
                    awaitRowLockWait(writerThread.get());
                    cleanupTask().cleanup();
                } catch (Exception exception) {
                    future.cancel(true);
                    throw new IllegalStateException(exception);
                }
            });
            holder[0] = pending.get().get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void adminUploadWaitsOnSqlLockWithoutHoldingJvmLockAndCoexistsWithCollection() throws Exception {
        LoginServiceImpl login = loginService(avatarWriter);
        Result[] result = new Result[1];
        long before = System.nanoTime();
        runAgainstCollection(() -> login.uploadAvatar(1L, png()), result);
        printEvidence("BE01-admin-upload-vs-gc", "code=" + result[0].getCode() + ",elapsedMs=" + (System.nanoTime() - before) / 1_000_000);
        assertEquals(200, result[0].getCode(), "回收持管理员锁时上传不得与其互等超时失败");
        String url = (String) result[0].getData();
        assertEquals(url, jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=1", String.class));
        assertEquals(1, fileCount());
    }

    @Test void adminMiniBindWaitsOnSqlLockWithoutHoldingJvmLockAndCoexistsWithCollection() throws Exception {
        String name = "e".repeat(32) + ".png";
        Files.writeString(avatarDirectory.resolve(name), "synthetic");
        redis.opsForValue().set("mini:upload:" + name, "1", java.time.Duration.ofMinutes(30));
        LoginServiceImpl login = loginService(avatarWriter);
        MiniAvatarServiceImpl mini = miniService(login, avatarWriter);
        MiniAvatarBindRequest request = new MiniAvatarBindRequest();
        request.setPath("/api/avatar/" + name);
        Result[] result = new Result[1];
        runAgainstCollection(() -> mini.bind(1L, request), result);
        printEvidence("BE01-admin-mini-bind-vs-gc", "code=" + result[0].getCode());
        assertEquals(200, result[0].getCode(), "回收持管理员锁时管理员Mini绑定不得与其互等超时失败");
        assertEquals("/api/avatar/" + name, jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=1", String.class));
        assertTrue(Files.exists(avatarDirectory.resolve(name)));
    }

    /** 提交阶段失败的事务管理器：先回滚再抛异常，模拟数据库提交失败。 */
    private PlatformTransactionManager failingCommit() {
        return new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return transactions.getTransaction(definition); }
            @Override public void commit(TransactionStatus status) { transactions.rollback(status); throw new IllegalStateException("synthetic-commit-failure"); }
            @Override public void rollback(TransactionStatus status) { transactions.rollback(status); }
        };
    }

    @Test void uploadCommitFailureReturnsErrorCleansNewFileAndKeepsDatabaseUnchanged() throws Exception {
        AvatarWriteExecutor failing = new AvatarWriteExecutor(sysUserDao, failingCommit());
        Result result = loginService(failing).uploadAvatar(1L, png());
        assertNotEquals(200, result.getCode(), "提交失败不得返回成功");
        assertEquals(0, fileCount(), "提交失败后新文件必须清理");
        assertNull(jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=1", String.class));
    }

    @Test void miniBindCommitFailureReturnsErrorAndRollsBackBinding() throws Exception {
        String name = "f".repeat(32) + ".png";
        Files.writeString(avatarDirectory.resolve(name), "synthetic");
        redis.opsForValue().set("mini:upload:" + name, "1", java.time.Duration.ofMinutes(30));
        AvatarWriteExecutor failing = new AvatarWriteExecutor(sysUserDao, failingCommit());
        MiniAvatarServiceImpl mini = miniService(loginService(failing), failing);
        MiniAvatarBindRequest request = new MiniAvatarBindRequest();
        request.setPath("/api/avatar/" + name);
        assertNotEquals(200, mini.bind(1L, request).getCode());
        assertNull(jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=1", String.class));
        assertTrue(Files.exists(avatarDirectory.resolve(name)), "已上传文件仍由上传映射保护，绑定失败不得删除");
    }

    @Test void uploadExceptionAfterRealCommitKeepsTheCommittedAvatarFile() throws Exception {
        PlatformTransactionManager afterCommitFailure = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return transactions.getTransaction(definition); }
            @Override public void commit(TransactionStatus status) {
                transactions.commit(status);
                throw new IllegalStateException("synthetic-after-real-commit");
            }
            @Override public void rollback(TransactionStatus status) { transactions.rollback(status); }
        };
        Result result = loginService(new AvatarWriteExecutor(sysUserDao, afterCommitFailure)).uploadAvatar(1L, png());
        assertNotEquals(200, result.getCode());
        String url = jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=1", String.class);
        assertNotNull(url, "该故障在真实提交之后抛出");
        Path committedFile = avatarDirectory.resolve(url.substring(url.lastIndexOf('/') + 1));
        assertTrue(Files.exists(committedFile));
        assertEquals(1, fileCount(), "不得根据异常删除已提交的头像");
    }

    @Test void failedUploadActionRollsBackDatabaseBeforeCleaningNewFile() throws Exception {
        LoginDao failedUpdate = org.mockito.Mockito.mock(LoginDao.class, org.mockito.AdditionalAnswers.delegatesTo(loginDao));
        org.mockito.Mockito.doAnswer(call -> {
            loginDao.updateAvatar(call.getArgument(0), call.getArgument(1));
            throw new IllegalStateException("synthetic-after-avatar-update");
        }).when(failedUpdate).updateAvatar(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyString());
        LoginServiceImpl service = loginService(avatarWriter);
        ReflectionTestUtils.setField(service, "loginDao", failedUpdate);
        assertNotEquals(200, service.uploadAvatar(1L, png()).getCode());
        assertNull(jdbc.queryForObject("SELECT avatar FROM sys_user WHERE id=1", String.class));
        assertEquals(0, fileCount(), "动作失败且明确回滚后才清理新文件");
    }

    @Test void legacyAliasToCollectedFileIsRejectedAndExistingFileIsNormalized() throws Exception {
        Object target = AopTestUtils.getUltimateTargetObject(doctors);
        Object oldDirectory = ReflectionTestUtils.getField(target, "avatarDirectory");
        ReflectionTestUtils.setField(target, "avatarDirectory", avatarDirectory.toString());
        try {
            String missing = "9".repeat(32) + ".png";
            Doctor rejected = person("Synthetic alias missing"); rejected.setId(1L);
            rejected.setAvatar("/api/login/avatar/" + missing);
            assertThrows(IllegalArgumentException.class, () -> doctors.update(rejected));
            assertNull(jdbc.queryForObject("SELECT avatar FROM doctor WHERE id=1", String.class));

            String present = "8".repeat(32) + ".png";
            Files.writeString(avatarDirectory.resolve(present), "synthetic");
            Doctor accepted = person("Synthetic alias present"); accepted.setId(1L);
            accepted.setAvatar("/api/login/avatar/" + present + "?v=1#photo");
            assertTrue(doctors.update(accepted));
            assertEquals("/api/avatar/" + present, jdbc.queryForObject("SELECT avatar FROM doctor WHERE id=1", String.class));
        } finally { ReflectionTestUtils.setField(target, "avatarDirectory", oldDirectory); }
    }

    private Doctor person(String name) {
        Doctor doctor = new Doctor(); doctor.setName(name); doctor.setDepartmentId(1L); doctor.setStatus(1);
        return doctor;
    }
}
