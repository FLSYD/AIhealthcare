package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.entity.SysLoginLog;
import com.aaa.aihealthcare.service.SysLoginLogService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequireRoles("ADMIN")
@RequestMapping("/api/sys-login-log")
public class SysLoginLogController {

    @Autowired
    private SysLoginLogService sysLoginLogService;

    @GetMapping("/page")
    public Result page(@RequestParam(required = false) String username,
                       @RequestParam(defaultValue = "1") Integer pageNum,
                       @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<SysLoginLog> pageInfo = sysLoginLogService.page(username, pageNum, pageSize);
        return Result.ok(pageInfo);
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        if (id == null || id < 1) {
            return Result.error("日志ID必须为正整数");
        }
        SysLoginLog loginLog = sysLoginLogService.getById(id);
        if (loginLog == null) {
            return Result.error("日志不存在");
        }
        return Result.ok(loginLog);
    }

    @PostMapping
    public Result save(@RequestBody SysLoginLog loginLog) {
        try {
            return sysLoginLogService.save(loginLog) ? Result.ok() : Result.error("保存失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @OperationLog(module = "登录日志", action = "删除登录日志")
    public Result deleteById(@PathVariable Long id) {
        if (id == null || id < 1) {
            return Result.error("日志ID必须为正整数");
        }
        return sysLoginLogService.deleteById(id) ? Result.ok() : Result.error("删除失败");
    }

    @DeleteMapping("/batch")
    @OperationLog(module = "登录日志", action = "批量删除登录日志")
    public Result deleteBatch(@RequestBody List<Long> ids) {
        try {
            return sysLoginLogService.deleteBatch(ids) ? Result.ok() : Result.error("删除失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
