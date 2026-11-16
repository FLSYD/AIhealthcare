package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.LoginSession;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.UserValidation;
import com.aaa.aihealthcare.dao.MiniAuthDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.LoginService;
import com.aaa.aihealthcare.service.MiniAuthService;
import com.aaa.aihealthcare.service.WechatIdentityClient;
import com.aaa.aihealthcare.vo.MiniAuthResponseVO;
import com.aaa.aihealthcare.vo.MiniLoginRequest;
import com.aaa.aihealthcare.vo.MiniUserVO;
import com.aaa.aihealthcare.vo.WxLoginRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class MiniAuthServiceImpl implements MiniAuthService {
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final int OPENID_HASH_BYTES = 16;

    private final LoginService loginService;
    private final MiniAuthDao miniAuthDao;
    private final SysUserDao sysUserDao;
    private final WechatIdentityClient wechatIdentityClient;
    private final TransactionTemplate transactionTemplate;

    public MiniAuthServiceImpl(LoginService loginService,
            MiniAuthDao miniAuthDao,
            SysUserDao sysUserDao,
            WechatIdentityClient wechatIdentityClient,
            PlatformTransactionManager transactionManager) {
        this.loginService = loginService;
        this.miniAuthDao = miniAuthDao;
        this.sysUserDao = sysUserDao;
        this.wechatIdentityClient = wechatIdentityClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Result login(MiniLoginRequest request) {
        if (request == null) {
            return Result.error("登录参数不能为空");
        }
        String username = UserValidation.normalizeUsername(request.getUsername());
        Result tokenResult = loginService.login(username, request.getPassword());
        if (tokenResult.getCode() != 200) {
            return tokenResult;
        }
        SysUser user = miniAuthDao.selectByUsername(username);
        if (!LoginSession.active(user)) {
            return Result.error("用户不存在或已被禁用");
        }
        return authenticated(tokenResult.getData().toString(), user);
    }

    @Override
    public Result wxLogin(WxLoginRequest request) {
        if (request == null) {
            return Result.error("微信登录参数不能为空");
        }
        if (request.getCode() == null || request.getCode().isBlank()
                || request.getCode().length() > 512) {
            return Result.error("微信登录凭证不能为空且不能超过512个字符");
        }
        String nickname = normalizeNickname(request.getNickname());
        if (nickname != null && nickname.length() > 50) {
            return Result.error("昵称不能超过50个字符");
        }

        String openid;
        try {
            openid = wechatIdentityClient.exchangeCode(request.getCode());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(exception.getMessage());
        }
        if (openid == null || openid.isBlank() || openid.length() > 64
                || !openid.equals(openid.strip())) {
            return Result.error("微信登录服务未返回有效身份，请重试");
        }

        SysUser user;
        try {
            user = transactionTemplate.execute(status -> findOrCreateWechatUser(openid, nickname));
        } catch (IllegalStateException exception) {
            return Result.error(exception.getMessage());
        }
        if (user == null) {
            return Result.error("微信账号创建失败，请重试");
        }

        Result tokenResult = loginService.issueSession(user);
        if (tokenResult.getCode() != 200) {
            return tokenResult;
        }
        return authenticated(tokenResult.getData().toString(), user);
    }

    @Override
    public Result getCurrentUser(Long userId) {
        Result result = loginService.getById(userId);
        if (result.getCode() != 200 || !(result.getData() instanceof SysUser user)) {
            return result;
        }
        return Result.ok(MiniUserVO.from(user));
    }

    private SysUser findOrCreateWechatUser(String openid, String nickname) {
        sysUserDao.lockActiveAdminIds();
        SysUser existing = miniAuthDao.selectByOpenidForUpdate(openid);
        if (existing != null) {
            if (!LoginSession.active(existing)) {
                throw new IllegalStateException("微信账号已被禁用或删除，请联系管理员");
            }
            return existing;
        }

        String username = usernameForOpenid(openid);
        String passwordHash = PASSWORD_ENCODER.encode(UUID.randomUUID().toString()
                + UUID.randomUUID());
        try {
            if (miniAuthDao.insertWechatUser(username, passwordHash, nickname, openid) != 1) {
                throw new IllegalStateException("微信账号创建失败，请重试");
            }
        } catch (DataIntegrityViolationException exception) {
            SysUser raced = miniAuthDao.selectByOpenidForUpdate(openid);
            if (!LoginSession.active(raced)) {
                throw new IllegalStateException("微信账号创建失败，请重试");
            }
            return raced;
        }
        SysUser created = miniAuthDao.selectByOpenidForUpdate(openid);
        if (!LoginSession.active(created) || !"USER".equals(created.getRole())) {
            throw new IllegalStateException("微信账号创建失败，请联系管理员");
        }
        return created;
    }

    private String usernameForOpenid(String openid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(openid.getBytes(StandardCharsets.UTF_8));
            return "wx_" + HexFormat.of().formatHex(Arrays.copyOf(digest, OPENID_HASH_BYTES));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("微信账号创建失败，请重试", exception);
        }
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null) {
            return null;
        }
        String normalized = nickname.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private Result authenticated(String token, SysUser user) {
        return Result.ok(new MiniAuthResponseVO(token, MiniUserVO.from(user)));
    }
}
