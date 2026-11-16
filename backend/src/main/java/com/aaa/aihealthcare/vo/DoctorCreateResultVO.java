package com.aaa.aihealthcare.vo;

/** 管理员创建成功时返回账号及初始密码；数据库只保存密码散列。 */
public record DoctorCreateResultVO(Long id, String loginUsername, String initialPassword) {
}
