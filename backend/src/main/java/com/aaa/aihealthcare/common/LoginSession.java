package com.aaa.aihealthcare.common;

import com.aaa.aihealthcare.entity.SysUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class LoginSession {
    public static final String PREFIX = "login:token:";
    public static final String REVISION_PREFIX = "login:user:revision:";

    private LoginSession() {
    }

    public static boolean active(SysUser user) {
        return user != null && Integer.valueOf(1).equals(user.getStatus())
                && Integer.valueOf(0).equals(user.getDeleted());
    }

    // 凭证同时绑定密码、角色和医生身份，避免并发登录继承变更后的权限。
    public static String value(SysUser user, String revision) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((user.getPassword() + "|" + user.getRole() + "|" + user.getDoctorId())
                            .getBytes(StandardCharsets.UTF_8));
            return user.getId() + ":" + HexFormat.of().formatHex(digest) + ":" + revision;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
