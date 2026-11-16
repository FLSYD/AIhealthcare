package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.LoginSession;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.dao.LoginDao;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.SysLoginLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceImplTests {

    @Mock
    private LoginDao loginDao;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SysLoginLogService loginLogService;

    @InjectMocks
    private LoginServiceImpl service;

    @Test
    void loginNormalizesUsernameBeforeLookup() {
        SysUser user = activeUser();
        user.setPassword(new BCryptPasswordEncoder().encode("secret12"));
        when(loginDao.getByUsername("admin")).thenReturn(user);
        when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LoginSession.REVISION_PREFIX + user.getId())).thenReturn("revision");

        Result result = service.login("  admin  ", "secret12");

        assertEquals(200, result.getCode());
        verify(loginDao).getByUsername("admin");
    }

    @Test
    void loginRejectsPasswordAboveBcryptByteLimitBeforeLookup() {
        Result result = service.login("admin", "1234567890".repeat(8));

        assertEquals(500, result.getCode());
        verifyNoInteractions(loginDao, stringRedisTemplate);
    }

    @Test
    void completedSessionSurvivesAttemptCleanupFailure() {
        SysUser user = activeUser();
        user.setPassword(new BCryptPasswordEncoder().encode("synthetic-cleanup-password"));
        when(loginDao.getByUsername("admin")).thenReturn(user);
        when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(LoginSession.REVISION_PREFIX + user.getId())).thenReturn("revision");
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic-delete-failure"))
                .when(stringRedisTemplate).delete(org.mockito.ArgumentMatchers.startsWith("login:attempt:"));
        assertEquals(200, service.login("admin", "synthetic-cleanup-password").getCode());
    }

    @Test
    void updateProfileRejectsNullRequest() {
        Result result = service.updateProfile(1L, null);

        assertEquals(500, result.getCode());
        assertEquals("用户参数不能为空", result.getMsg());
        verifyNoInteractions(loginDao);
    }

    private SysUser activeUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("ADMIN");
        user.setStatus(1);
        user.setDeleted(0);
        return user;
    }
}
