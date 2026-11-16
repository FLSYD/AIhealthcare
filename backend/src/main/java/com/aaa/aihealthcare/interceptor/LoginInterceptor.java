package com.aaa.aihealthcare.interceptor;

import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.LoginSession;
import com.aaa.aihealthcare.dao.LoginDao;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.AnonymousAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.core.annotation.AnnotatedElementUtils;
import java.util.Arrays;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoginDao loginDao;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {

        if (handler instanceof HandlerMethod publicMethod
                && publicMethod.getBeanType().getPackageName().startsWith("com.aaa.aihealthcare.controller")
                && AnnotatedElementUtils.hasAnnotation(publicMethod.getMethod(), AnonymousAccess.class)) {
            return true;
        }

        // 图片地址可直接用于 img 标签；上传仍需要登录。
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && ("/api/avatar/{filename}".equals(
                        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                    || "/api/login/avatar/{filename}".equals(
                        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)))) {
            return true;
        }

        // 1. 读取请求头中的登录凭证。
        String authorization = request.getHeader("Authorization");

        if (authorization == null
                || !authorization.regionMatches(
                        true, 0, "Bearer ", 0, 7)) {
            return reject(response);
        }

        // 2. 去掉 Bearer 前缀，得到 token。
        String token = authorization.substring(7).trim();

        if (token.isEmpty()) {
            return reject(response);
        }

        // 3. 根据 token 查询 Redis 中的登录状态。
        String session = stringRedisTemplate.opsForValue().get(LoginSession.PREFIX + token);

        if (session == null || !session.contains(":")) {
            return reject(response);
        }

        // 4. 将当前登录用户 ID 放入本次请求。
        SysUser user;
        try {
            Long userId = Long.valueOf(session.substring(0, session.indexOf(':')));
            user = loginDao.getById(userId);
            String revision = stringRedisTemplate.opsForValue().get(LoginSession.REVISION_PREFIX + userId);
            if (revision == null || !LoginSession.active(user) || user.getPassword() == null
                    || !session.equals(LoginSession.value(user, revision))) {
                stringRedisTemplate.delete(LoginSession.PREFIX + token);
                return reject(response);
            }
            request.setAttribute("loginUserId", userId);
            request.setAttribute("loginUser", user);
            request.setAttribute("loginUsername", user.getUsername());
        } catch (NumberFormatException exception) {
            return reject(response);
        }

        // 权限绑定实际处理方法，前端菜单、角色参数和等价 URL 都不能改变授权。
        if (handler instanceof HandlerMethod method
                && method.getBeanType().getPackageName().startsWith("com.aaa.aihealthcare.controller")) {
            RequireRoles roles = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireRoles.class);
            if (roles == null) {
                roles = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireRoles.class);
            }
            // 新增后台接口必须明确标注角色，避免合并代码后默认向所有登录用户开放。
            if (roles == null || !Arrays.asList(roles.value()).contains(user.getRole())) {
                return reject(response, HttpServletResponse.SC_FORBIDDEN, "当前账号无权访问此功能");
            }
        }

        // 5. 保存本次 token，供后续接口使用。
        request.setAttribute("loginToken", token);

        // 6. 校验通过，继续执行 Controller。
        return true;
    }

    private boolean reject(
            HttpServletResponse response) throws IOException {
        return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已过期");
    }

    private boolean reject(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");

        Result result =
                new Result(code, message, null);

        response.getWriter()
                .write(objectMapper.writeValueAsString(result));

        return false;
    }
}
