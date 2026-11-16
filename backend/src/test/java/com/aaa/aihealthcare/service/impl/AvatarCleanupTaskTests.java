package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.LoginDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarCleanupTaskTests {
    @TempDir Path directory;

    @Test void removesOnlyOldUnreferencedFilesAndKeepsMappedReferencedAndFresh() throws Exception {
        LoginDao users = mock(LoginDao.class); StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Path orphan = avatar('a', true), referenced = avatar('b', true), pending = avatar('c', true), fresh = avatar('d', false);
        Path foreign = Files.writeString(directory.resolve("foreign.jpg"), "fixture");
        when(users.selectAvatarReferences()).thenReturn(List.of("/api/avatar/" + referenced.getFileName() + "?v=1#photo"));
        when(values.get("mini:upload:" + pending.getFileName())).thenReturn("10");
        AvatarCleanupTask task = task(users, redis);
        ReflectionTestUtils.setField(task, "avatarDirectory", directory.toString());
        task.cleanup();
        assertFalse(Files.exists(orphan));
        for (Path retained : List.of(referenced, pending, fresh, foreign)) assertTrue(Files.exists(retained));
    }

    @Test void databaseOrRedisFailureNeverDeletesUnverifiedFiles() throws Exception {
        LoginDao users = mock(LoginDao.class); StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Path orphan = avatar('e', true);
        AvatarCleanupTask task = task(users, redis);
        ReflectionTestUtils.setField(task, "avatarDirectory", directory.toString());
        when(users.selectAvatarReferences()).thenThrow(new IllegalStateException("synthetic-db-down"));
        task.cleanup(); assertTrue(Files.exists(orphan));
        doReturn(List.of()).when(users).selectAvatarReferences();
        when(redis.opsForValue()).thenThrow(new IllegalStateException("synthetic-redis-down"));
        task.cleanup(); assertTrue(Files.exists(orphan));
    }

    private Path avatar(char value, boolean old) throws Exception {
        Path file = Files.writeString(directory.resolve(String.valueOf(value).repeat(32)+".png"), "synthetic");
        if (old) Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(172800)));
        return file;
    }

    private AvatarCleanupTask task(LoginDao users, StringRedisTemplate redis) {
        var accounts = mock(com.aaa.aihealthcare.dao.SysUserDao.class);
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> new org.springframework.transaction.support.SimpleTransactionStatus());
        return new AvatarCleanupTask(users, redis, accounts, manager);
    }
}
