package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AvatarStorage;
import com.aaa.aihealthcare.dao.LoginDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 每小时回收超过24小时且无数据库引用、无有效上传映射的头像；依赖失败时保留文件。 */
@Service
public class AvatarCleanupTask {
    private final LoginDao users;
    private final StringRedisTemplate redis;
    private final SysUserDao accounts;
    private final TransactionTemplate transactions;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AvatarCleanupTask.class);
    @Value("${healthcare.avatar-dir:uploads/avatars}")
    private String avatarDirectory;

    public AvatarCleanupTask(LoginDao users, StringRedisTemplate redis, SysUserDao accounts, PlatformTransactionManager manager) {
        this.users = users;
        this.redis = redis;
        this.accounts = accounts;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
    public void cleanup() {
        try {
            transactions.executeWithoutResult(status -> {
                // 与人员/账号写操作同序，SQL锁覆盖提交；不能先持JVM锁再等待管理员锁。
                accounts.lockActiveAdminIds();
                synchronized (AvatarStorage.LOCK) {
                    try { cleanupFiles(); }
                    catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
                }
            });
        } catch (RuntimeException exception) {
            LOG.warn("头像回收未完成，将稍后重试：{}", exception.getClass().getSimpleName());
        }
    }

    private void cleanupFiles() throws IOException {
        Path directory = Path.of(avatarDirectory).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        var references = users.selectAvatarReferences();
        if (references == null) return;
        var retained = new HashSet<String>();
        for (String reference : references) {
            if (reference == null) continue;
            String decoded = java.net.URLDecoder.decode(reference, java.nio.charset.StandardCharsets.UTF_8);
            var names = java.util.regex.Pattern.compile("[a-f0-9]{32}\\.(jpg|png|gif|webp)").matcher(decoded);
            while (names.find()) retained.add(names.group());
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.matches("[a-f0-9]{32}\\.(jpg|png|gif|webp)") || retained.contains(name)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) continue;
                if (redis.opsForValue().get("mini:upload:" + name) != null) continue;
                Files.deleteIfExists(file);
            }
        }
    }
}
