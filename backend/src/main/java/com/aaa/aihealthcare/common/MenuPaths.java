package com.aaa.aihealthcare.common;

import java.util.Map;
import java.util.Set;

public final class MenuPaths {
    public static final String ADMIN_HOME = "/dashboard";
    public static final String DOCTOR_HOME = "/doctor/overview";
    public static final String PHARMACY_HOME = "/pharmacy";

    private static final Map<String, String> LEGACY_PATHS = Map.ofEntries(
            Map.entry("/admin/dashboard", ADMIN_HOME),
            Map.entry("/admin/departments", "/department"),
            Map.entry("/admin/doctors", "/doctor"),
            Map.entry("/admin/schedules", "/schedule"),
            Map.entry("/admin/appointments", "/appointment"),
            Map.entry("/admin/medicines", "/medicine"),
            Map.entry("/admin/users", "/sysUser"),
            Map.entry("/admin/logs", "/sys-login-log"),
            Map.entry("/admin/menus", "/menu"),
            Map.entry("/admin/doctor/dashboard", DOCTOR_HOME),
            Map.entry("/admin/doctor/schedules", "/doctor/schedules"),
            Map.entry("/admin/doctor/appointments", "/doctor/appointments"));

    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry(ADMIN_HOME, "dashboard"),
            Map.entry("/department", "department"),
            Map.entry("/doctor", "doctor"),
            Map.entry("/schedule", "schedule"),
            Map.entry("/appointment", "appointment"),
            Map.entry("/medicine", "medicine"),
            Map.entry("/sysUser", "sysUser"),
            Map.entry("/sys-login-log", "sys-login-log"),
            Map.entry("/menu", "menu"),
            Map.entry(DOCTOR_HOME, "doctor-overview"),
            Map.entry("/doctor/schedules", "doctor-schedules"),
            Map.entry("/doctor/appointments", "doctor-appointments"),
            Map.entry(PHARMACY_HOME, "pharmacy"));

    /**
     * 路径允许的角色集合。
     * 早期设计每个路径只对应一个角色；配药师需要查看药品，因此 /medicine 允许两个角色。
     */
    private static final java.util.Map<String, java.util.Set<String>> ALLOWED_ROLE_SETS = java.util.Map.ofEntries(
            java.util.Map.entry(ADMIN_HOME, java.util.Set.of("ADMIN")),
            java.util.Map.entry("/department", java.util.Set.of("ADMIN")),
            java.util.Map.entry("/doctor", java.util.Set.of("ADMIN")),
            java.util.Map.entry("/schedule", java.util.Set.of("ADMIN")),
            java.util.Map.entry("/appointment", java.util.Set.of("ADMIN")),
            // 药品管理：管理员可增删改，配药师只读
            java.util.Map.entry("/medicine", java.util.Set.of("ADMIN", "PHARMACIST")),
            java.util.Map.entry("/sysUser", java.util.Set.of("ADMIN")),
            java.util.Map.entry("/sys-login-log", java.util.Set.of("ADMIN")),
            java.util.Map.entry("/menu", java.util.Set.of("ADMIN")),
            java.util.Map.entry(DOCTOR_HOME, java.util.Set.of("DOCTOR")),
            java.util.Map.entry("/doctor/schedules", java.util.Set.of("DOCTOR")),
            java.util.Map.entry("/doctor/appointments", java.util.Set.of("DOCTOR")),
            java.util.Map.entry(PHARMACY_HOME, java.util.Set.of("PHARMACIST")));

    private MenuPaths() {
    }

    /** 路径允许的全部角色。 */
    public static java.util.Set<String> rolesOf(String canonicalPath) {
        java.util.Set<String> roles = ALLOWED_ROLE_SETS.get(canonicalize(canonicalPath));
        return roles == null ? java.util.Set.of() : roles;
    }

    /** 判断某角色是否允许访问该路径。 */
    public static boolean allowsRole(String canonicalPath, String role) {
        return rolesOf(canonicalPath).contains(role);
    }

    /** 主角色（用于兼容旧逻辑，返回排序后的第一个）。 */
    public static String roleOf(String canonicalPath) {
        java.util.Set<String> roles = rolesOf(canonicalPath);
        return roles.stream().sorted().findFirst().orElse(null);
    }

    public static String canonicalize(String path) {
        String value = path == null ? "" : path.strip();
        String canonical = LEGACY_PATHS.get(value);
        if (canonical == null && KEYS.containsKey(value)) {
            canonical = value;
        }
        if (canonical == null) {
            throw new IllegalArgumentException("不支持或未注册的菜单路径");
        }
        return canonical;
    }

    public static String tryCanonicalize(String path) {
        try {
            return canonicalize(path);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String keyOf(String canonicalPath) {
        return KEYS.get(canonicalize(canonicalPath));
    }

    public static boolean isDefaultHome(String canonicalPath) {
        String canonical = tryCanonicalize(canonicalPath);
        return ADMIN_HOME.equals(canonical) || DOCTOR_HOME.equals(canonical) || PHARMACY_HOME.equals(canonical);
    }

    public static boolean isLegacyPath(String path) {
        return path != null && LEGACY_PATHS.containsKey(path.strip());
    }

    public static Set<String> canonicalPaths() {
        return KEYS.keySet();
    }
}
