package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.MenuService;
import com.aaa.aihealthcare.vo.MenuManageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/menus")
@RequireRoles("ADMIN")
public class MenuController {
    @Autowired
    private MenuService menuService;

    @GetMapping
    @RequireRoles({"ADMIN", "DOCTOR", "PHARMACIST", "USER"})
    public Result menus(@RequestAttribute("loginUser") SysUser user) {
        return Result.ok(menuService.listMine(user.getRole()));
    }

    @GetMapping("/manage")
    public Result manage(@RequestParam(required = false) String keyword) {
        return Result.ok(menuService.listManage(keyword));
    }

    @PostMapping
    @OperationLog(module = "菜单管理", action = "新增菜单")
    public Result create(@RequestBody MenuManageRequest request) {
        try {
            return Result.ok(menuService.create(request));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(exception.getMessage());
        }
    }

    @PutMapping("/{id}")
    @OperationLog(module = "菜单管理", action = "修改菜单")
    public Result update(@PathVariable Long id, @RequestBody MenuManageRequest request) {
        try {
            return Result.ok(menuService.update(id, request));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(exception.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @OperationLog(module = "菜单管理", action = "删除菜单")
    public Result delete(@PathVariable Long id) {
        try {
            int deleted = menuService.delete(id);
            return deleted == 1 ? Result.ok(deleted) : Result.error("菜单不存在");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(exception.getMessage());
        }
    }

    @DeleteMapping({"", "/batch"})
    @OperationLog(module = "菜单管理", action = "批量删除菜单")
    public Result deleteBatch(@RequestBody List<Long> ids) {
        try {
            return Result.ok(menuService.deleteBatch(ids));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(exception.getMessage());
        }
    }
}
