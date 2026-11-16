package com.aaa.aihealthcare.common;

import com.aaa.aihealthcare.entity.SysUser;
import java.nio.charset.StandardCharsets;

public final class UserValidation {
    private UserValidation() {
    }

    public static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        // 同时处理普通空格、全角空格和不换行空格，新增与登录共用此规则。
        int start = 0;
        int end = username.length();
        while (start < end && usernameSpace(username.codePointAt(start))) {
            start += Character.charCount(username.codePointAt(start));
        }
        while (start < end && usernameSpace(username.codePointBefore(end))) {
            end -= Character.charCount(username.codePointBefore(end));
        }
        return username.substring(start, end);
    }

    private static boolean usernameSpace(int codePoint) {
        return codePoint <= 0x20 || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    public static String password(String password, int minLength) {
        if (password == null || password.isBlank()) {
            return "密码不能为空";
        }
        if (password.length() < minLength || password.length() > 50) {
            return "密码长度必须为" + minLength + "到50个字符";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return "密码编码后不能超过72字节";
        }
        return null;
    }

    public static String profile(SysUser user) {
        if (user == null) {
            return "用户参数不能为空";
        }
        if (user.getNickname() != null && user.getNickname().length() > 50) {
            return "昵称不能超过50个字符";
        }
        String email = user.getEmail();
        if (email != null && !email.isEmpty()
                && (email.length() > 100 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            return "邮箱格式不正确或超过100个字符";
        }
        String phone = user.getPhone();
        if (phone != null && !phone.isEmpty() && !phone.matches("1[3-9][0-9]{9}")) {
            return "请输入有效的11位手机号";
        }
        return null;
    }
}
