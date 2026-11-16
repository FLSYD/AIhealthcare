package com.aaa.aihealthcare.common;

import com.aaa.aihealthcare.vo.MenuVO;
import com.aaa.aihealthcare.vo.RolePermissionsVO;
import java.util.List;

public final class RolePermissions {
    private RolePermissions() {
    }

    public static RolePermissionsVO forRole(String role) {
        if ("ADMIN".equals(role)) {
            return new RolePermissionsVO(role, true, "/dashboard", List.of(
                    new MenuVO("dashboard", "数据概览", "/dashboard"),
                    new MenuVO("department", "科室管理", "/department"),
                    new MenuVO("doctor", "医生管理", "/doctor"),
                    new MenuVO("schedule", "排班管理", "/schedule"),
                    new MenuVO("medicine", "药品管理", "/medicine"),
                    new MenuVO("sysUser", "用户管理", "/sysUser"),
                    new MenuVO("sys-login-log", "系统日志", "/sys-login-log"),
                    new MenuVO("menu", "菜单管理", "/menu"),
                    new MenuVO("appointment", "预约管理", "/appointment")),
                    List.of("dashboard:read", "department:manage", "doctor:manage", "schedule:manage",
                            "medicine:manage", "user:manage", "login-log:manage", "operation-log:manage",
                            "menu:manage", "appointment:manage", "profile:read", "profile:update",
                            "password:update"));
        }
        if ("DOCTOR".equals(role)) {
            return new RolePermissionsVO(role, true, "/doctor/overview", List.of(
                    new MenuVO("doctor-overview", "工作概览", "/doctor/overview"),
                    new MenuVO("doctor-schedules", "我的排班", "/doctor/schedules"),
                    new MenuVO("doctor-appointments", "我的预约", "/doctor/appointments")),
                    List.of("doctor-workspace:read", "schedule:read:self", "appointment:read:self",
                            "consultation:start:self", "consultation:record:self", "prescription:create:self",
                            "consultation:complete:self", "prescription:dispense:self", "medicine:select",
                            "profile:read", "profile:update", "password:update"));
        }
        // 配药师：按已付款处方发药；可只读查看药品信息，不参与诊疗流程。
        if ("PHARMACIST".equals(role)) {
            return new RolePermissionsVO(role, true, "/pharmacy", List.of(
                    new MenuVO("pharmacy", "配药工作台", "/pharmacy"),
                    new MenuVO("medicine", "药品管理", "/medicine")),
                    List.of("pharmacy:read", "prescription:dispense", "medicine:read",
                            "profile:read", "profile:update", "password:update"));
        }
        // 普通患者保留个人账号接口，不具备后台管理页面权限。
        return new RolePermissionsVO(role, false, null, List.of(), List.of());
    }
}
