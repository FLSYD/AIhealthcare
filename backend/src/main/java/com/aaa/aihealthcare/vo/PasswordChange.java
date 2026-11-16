package com.aaa.aihealthcare.vo;

import lombok.Data;

@Data
public class PasswordChange {
    private String oldPassword;
    private String newPassword;
    private String confirmPassword;
}
