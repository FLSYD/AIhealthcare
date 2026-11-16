package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.LoginSession;
import com.aaa.aihealthcare.common.AvatarValidation;
import com.aaa.aihealthcare.common.ClientIp;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.UserValidation;
import com.aaa.aihealthcare.dao.LoginDao;
import com.aaa.aihealthcare.service.SysLoginLogService;
import com.aaa.aihealthcare.vo.PasswordChange;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.LoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.Locale;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class LoginServiceImpl implements LoginService {
    @Autowired
    private LoginDao loginDao;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SysLoginLogService sysLoginLogService;

    @Autowired
    private AvatarWriteExecutor avatarWriter;

    @Value("${healthcare.avatar-dir:uploads/avatars}")
    private String avatarDirectory;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    // 原子增加计数并设置固定窗口，避免并发越过阈值或计数永久不失效。
    private static final DefaultRedisScript<Long> LOGIN_ATTEMPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class);
    private static final int MAX_LOGIN_ATTEMPTS = 10;

    @Override
    public Result login(String username, String password) {
        username = UserValidation.normalizeUsername(username);
        if (username == null || username.isEmpty() || password == null || password.isBlank()) {
            return Result.error("用户名和密码不能为空");
        }
        if (username.length() > 50 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return fail(username, "用户名或密码错误");
        }
        SysUser user = loginDao.getByUsername(username);
        String attemptKey = loginAttemptKey(user != null && user.getId() != null ? "id:" + user.getId() : "name:" + username);
        try {
            Long attempts = stringRedisTemplate.execute(LOGIN_ATTEMPT, List.of(attemptKey), "600");
            if (attempts == null) return fail(username, "登录校验暂不可用，请稍后重试");
            if (attempts > MAX_LOGIN_ATTEMPTS) return fail(username, "登录尝试过多，请10分钟后重试");
        } catch (RuntimeException exception) {
            return fail(username, "登录校验暂不可用，请稍后重试");
        }
        if (user == null || user.getPassword() == null
                || !passwordEncoder.matches(password, user.getPassword())) {
            return fail(username, "用户名或密码错误");
        }
        if (!LoginSession.active(user)) {
            return fail(username, "账号已被禁用");
        }
        Result result = issueSession(user);
        if (result.getCode() == 200) {
            try { stringRedisTemplate.delete(attemptKey); }
            catch (RuntimeException ignored) {
                // 会话已成功签发，计数清理失败只保守保留原窗口，不能改写登录结果。
            }
        }
        return result;
    }

    private String loginAttemptKey(String username) {
        try {
            return "login:attempt:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(username.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public Result issueSession(SysUser user) {
        if (!LoginSession.active(user) || user.getPassword() == null) {
            return Result.error("账号已被禁用");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        String revisionKey = LoginSession.REVISION_PREFIX + user.getId();
        String revision = stringRedisTemplate.opsForValue().get(revisionKey);
        if (revision == null) {
            stringRedisTemplate.opsForValue().setIfAbsent(revisionKey, UUID.randomUUID().toString());
            revision = stringRedisTemplate.opsForValue().get(revisionKey);
            if (revision == null) {
                return fail(user.getUsername(), "登录状态保存失败，请重试");
            }
        }
        stringRedisTemplate.opsForValue().set(LoginSession.PREFIX + token,
                LoginSession.value(user, revision), Duration.ofMinutes(30));
        recordLogin(user.getUsername(), 1, "登录成功");
        return Result.ok(token);
    }

    // 登录日志写入失败不能阻断登录流程，因此忽略异常。
    private void recordLogin(String username, int status, String message) {
        try {
            sysLoginLogService.record(username, ClientIp.current(), status, message);
        } catch (Exception ignored) {
        }
    }

    private Result fail(String username, String message) {
        recordLogin(username, 0, message);
        return Result.error(message);
    }

    @Override
    public Result getById(Long id) {
        SysUser user = loginDao.getById(id);
        return LoginSession.active(user) ? Result.ok(user) : Result.error("用户不存在或已被禁用");
    }

    @Override
    public Result logout(String token) {
        stringRedisTemplate.delete(LoginSession.PREFIX + token);
        return Result.ok();
    }

    @Override
    public Result updateProfile(Long userId, SysUser request) {
        if (request == null) {
            return Result.error("用户参数不能为空");
        }
        String error = UserValidation.profile(request);
        if (error != null) {
            return Result.error(error);
        }
        SysUser user = new SysUser();
        user.setId(userId);
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        if (loginDao.updateProfile(user) == 0) {
            return Result.error("个人信息保存失败");
        }
        return getById(userId);
    }

    @Override
    public Result changePassword(Long userId, PasswordChange request) {
        if (request == null || request.getOldPassword() == null || request.getOldPassword().isBlank()) {
            return Result.error("原密码不能为空");
        }
        String error = UserValidation.password(request.getNewPassword(), 6);
        if (error != null) {
            return Result.error(error);
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            return Result.error("两次输入的新密码不一致");
        }
        if (request.getOldPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            return Result.error("原密码错误");
        }
        SysUser user = loginDao.getById(userId);
        if (!LoginSession.active(user) || user.getPassword() == null
                || !passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            return Result.error("原密码错误");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            return Result.error("新密码不能与原密码相同");
        }
        if (loginDao.changePassword(userId, user.getPassword(), passwordEncoder.encode(request.getNewPassword())) != 1) {
            return Result.error("密码已变更或账号状态发生变化，请重新登录");
        }
        // 密码变化已使全部旧凭证失效；Redis 记录按原 TTL 回收，避免改密后清理失败产生歧义。
        return Result.ok();
    }

    @Override
    public Result uploadAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("请选择头像文件");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            return Result.error("头像不能超过2MB");
        }
        java.util.concurrent.atomic.AtomicReference<Path> saved = new java.util.concurrent.atomic.AtomicReference<>();
        return avatarWriter.write(() -> uploadAvatarProtected(userId, file, saved),
                () -> deleteQuietly(saved.get()), "头像保存失败，请稍后重试");
    }

    private Result uploadAvatarProtected(Long userId, MultipartFile file,
            java.util.concurrent.atomic.AtomicReference<Path> savedHolder) {
        try {
            byte[] bytes = file.getBytes();
            String extension = AvatarValidation.extension(bytes);
            if (extension == null) {
                if (!AvatarValidation.supportsWebp()) {
                    return Result.error("头像必须是完整有效的JPG、PNG或GIF图片，宽高不超过4096像素；当前环境暂不支持WEBP");
                }
                return Result.error("头像必须是完整有效的JPG、PNG、GIF、WEBP图片，宽高不超过4096像素");
            }
            Path directory = Path.of(avatarDirectory).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + extension;
            Path saved = directory.resolve(filename);
            Files.write(saved, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            savedHolder.set(saved);
            String url = "/api/avatar/" + filename;
            if (loginDao.updateAvatar(userId, url) != 1) {
                return Result.error("头像保存失败");
            }
            return Result.ok(url);
        } catch (IOException | RuntimeException exception) {
            // 文件仅在事务明确回滚后清理，未知提交结果保留以保护可能已生效的引用。
            return Result.error("头像保存失败，请稍后重试");
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 清理失败不覆盖原始保存结果。
        }
    }

    @Override
    public Resource getAvatar(String filename) {
        if (!filename.matches("[a-f0-9]{32}\\.(jpg|png|gif|webp)")) {
            return null;
        }
        Resource resource = new FileSystemResource(Path.of(avatarDirectory).toAbsolutePath().normalize().resolve(filename));
        return resource.isReadable() ? resource : null;
    }

}
