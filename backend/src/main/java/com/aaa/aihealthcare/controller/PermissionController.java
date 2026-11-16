package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RolePermissions;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.MenuService;
import com.aaa.aihealthcare.vo.RolePermissionsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequireRoles({"ADMIN", "DOCTOR", "USER", "PHARMACIST"})
public class PermissionController {
    @Autowired
    private MenuService menuService;

    @GetMapping("/permissions")
    public Result permissions(@RequestAttribute("loginUser") SysUser user) {
        RolePermissionsVO staticPermissions = RolePermissions.forRole(user.getRole());
        return Result.ok(new RolePermissionsVO(user.getRole(), staticPermissions.backendAccess(),
                staticPermissions.homePath(), menuService.listMine(user.getRole()),
                staticPermissions.permissions()));
    }
}
