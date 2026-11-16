package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.dao.LoginDao;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.SysLoginLogService;
import com.aaa.aihealthcare.service.impl.LoginServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 新建独立Redis进程、随机端口和口令；不连接6379或16379，不访问真实账号。 */
@EnabledIfSystemProperty(named = "healthcare.verify.enabled", matches = "true")
class LoginRateLimitRedisVerificationIT {
    @TempDir Path directory;

    @Test void concurrentAttemptsTtlAccountIsolationAndRecoveryUseRealRedisLua() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        assertNotEquals(6379, port); assertNotEquals(16379, port);
        String password = UUID.randomUUID().toString().replace("-", "");
        Path config = directory.resolve("redis.conf");
        Files.writeString(config, "bind 127.0.0.1\nport " + port + "\nprotected-mode yes\nsave \"\"\nappendonly no\nrequirepass " + password + "\n");
        Path binary = Path.of("../.tools/redis/redis-server.exe").toAbsolutePath().normalize();
        Process process = new ProcessBuilder(binary.toString(), config.toString()).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(directory.resolve("redis.log").toFile()).start();
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", port);
        configuration.setPassword(RedisPassword.of(password));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofMillis(200)).build();
        var factory = new LettuceConnectionFactory(configuration, client);
        StringRedisTemplate redis = null;
        try {
            factory.afterPropertiesSet(); factory.start();
            redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
            boolean ready = false;
            for (int i=0; i<30; i++) {
                assertTrue(process.isAlive(), "独立Redis进程启动失败");
                try (var connection = factory.getConnection()) { ready = "PONG".equals(connection.ping()); }
                catch (RuntimeException ignored) { Thread.sleep(100); }
                if (ready) break;
            }
            assertTrue(ready);
            LoginDao dao = mock(LoginDao.class);
            SysUser user = user(900001L, "synthetic_rate");
            when(dao.getByUsername(anyString())).thenReturn(user);
            LoginServiceImpl service = new LoginServiceImpl();
            ReflectionTestUtils.setField(service, "loginDao", dao);
            ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
            ReflectionTestUtils.setField(service, "sysLoginLogService", mock(SysLoginLogService.class));
            var pool = Executors.newFixedThreadPool(12);
            var calls = new ArrayList<java.util.concurrent.Callable<String>>();
            for (int i=0;i<12;i++) calls.add(() -> service.login("synthetic_rate", "wrong-synthetic").getMessage());
            List<java.util.concurrent.Future<String>> attempts;
            try { attempts = pool.invokeAll(calls, 15, TimeUnit.SECONDS); }
            finally { pool.shutdown(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)); }
            long limited = 0;
            for (var attempt : attempts) if (attempt.get().contains("登录尝试过多")) limited++;
            assertEquals(2, limited, "12次并发中只允许10次进入密码验证");
            assertNotEquals(200, service.login("same-account-alias", "Synthetic-Redis-Password").getCode());
            var keys = redis.keys("login:attempt:*"); assertNotNull(keys); assertEquals(1, keys.size());
            String key = keys.iterator().next();
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS); assertNotNull(ttl); assertTrue(ttl > 570 && ttl <= 600);
            SysUser other = user(900002L, "another"); when(dao.getByUsername("another")).thenReturn(other);
            assertEquals(200, service.login("another", "Synthetic-Redis-Password").getCode());
            redis.expire(key, Duration.ofMillis(150)); Thread.sleep(250);
            assertEquals(200, service.login("synthetic_rate", "Synthetic-Redis-Password").getCode());
            assertTrue(redis.keys("login:attempt:*").isEmpty());
            System.out.println("LOGIN_REDIS_EVIDENCE isolated=true,concurrency=12,limited=2,ttlSeconds="+ttl+",aliasSharesAccount=true,recovered=true");
        } finally {
            if (redis != null) try (var connection = factory.getConnection()) { connection.serverCommands().shutdown(); } catch (RuntimeException ignored) { }
            factory.destroy();
            if (process.isAlive()) process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    private SysUser user(Long id, String username) {
        SysUser user = new SysUser(); user.setId(id); user.setUsername(username);
        user.setRole("ADMIN"); user.setStatus(1); user.setDeleted(0);
        user.setPassword(new BCryptPasswordEncoder().encode("Synthetic-Redis-Password"));
        return user;
    }
}
