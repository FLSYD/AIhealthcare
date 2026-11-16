package com.aaa.aihealthcare.vo;

import java.util.List;

public record RolePermissionsVO(String role, boolean backendAccess, String homePath,
        List<MenuVO> menus, List<String> permissions) {
}
