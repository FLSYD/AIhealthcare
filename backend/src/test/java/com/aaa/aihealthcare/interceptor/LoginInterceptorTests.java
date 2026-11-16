package com.aaa.aihealthcare.interceptor;

import com.aaa.aihealthcare.common.LoginSession;
import com.aaa.aihealthcare.controller.SysOperationLogController;
import com.aaa.aihealthcare.dao.LoginDao;
import com.aaa.aihealthcare.entity.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginInterceptorTests {

    private static final String TOKEN = "test-token";
    private static final String REVISION = "revision-1";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private LoginDao loginDao;

    private LoginInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new LoginInterceptor();
        ReflectionTestUtils.setField(interceptor, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(interceptor, "loginDao", loginDao);
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void authenticatedAdminReceivesServerSideUsername() throws Exception {
        SysUser user = user("ADMIN");
        stubSession(user);
        MockHttpServletRequest request = request();
        request.addParameter("username", "forged-request-value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, operationLogHandler()));
        assertEquals("admin", request.getAttribute("loginUsername"));
        assertEquals(user, request.getAttribute("loginUser"));
    }

    @Test
    void ordinaryUserCannotAccessOperationLogManagement() throws Exception {
        stubSession(user("USER"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request(), response, operationLogHandler()));
        assertEquals(403, response.getStatus());
    }

    private void stubSession(SysUser user) {
        when(valueOperations.get(LoginSession.PREFIX + TOKEN))
                .thenReturn(LoginSession.value(user, REVISION));
        when(valueOperations.get(LoginSession.REVISION_PREFIX + user.getId())).thenReturn(REVISION);
        when(loginDao.getById(user.getId())).thenReturn(user);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sys-operation-log/page");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private HandlerMethod operationLogHandler() throws NoSuchMethodException {
        return new HandlerMethod(new SysOperationLogController(), SysOperationLogController.class.getMethod(
                "page", String.class, String.class, Integer.class, Integer.class));
    }

    private SysUser user(String role) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("$2a$10$abcdefghijklmnopqrstuv");
        user.setRole(role);
        user.setStatus(1);
        user.setDeleted(0);
        return user;
    }
}
