package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.entity.SysOperationLog;
import com.aaa.aihealthcare.service.SysOperationLogService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequireRoles("ADMIN")
@RequestMapping("/api/sys-operation-log")
public class SysOperationLogController {

    @Autowired
    private SysOperationLogService sysOperationLogService;

    @GetMapping("/page")
    public Result page(@RequestParam(required = false) String username,
                       @RequestParam(required = false) String module,
                       @RequestParam(defaultValue = "1") Integer pageNum,
                       @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<SysOperationLog> pageInfo =
                sysOperationLogService.page(username, module, pageNum, pageSize);
        return Result.ok(pageInfo);
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        if (id == null || id < 1) {
            return Result.error("日志ID必须为正整数");
        }
        SysOperationLog operationLog = sysOperationLogService.getById(id);
        if (operationLog == null) {
            return Result.error("日志不存在");
        }
        return Result.ok(operationLog);
    }

    @PostMapping
    public Result save(@RequestBody SysOperationLog operationLog) {
        try {
            return sysOperationLogService.save(operationLog) ? Result.ok() : Result.error("保存失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @OperationLog(module = "操作日志", action = "删除操作日志")
    public Result deleteById(@PathVariable Long id) {
        if (id == null || id < 1) {
            return Result.error("日志ID必须为正整数");
        }
        return sysOperationLogService.deleteById(id) ? Result.ok() : Result.error("删除失败");
    }

    @DeleteMapping("/batch")
    @OperationLog(module = "操作日志", action = "批量删除操作日志")
    public Result deleteBatch(@RequestBody List<Long> ids) {
        try {
            return sysOperationLogService.deleteBatch(ids) ? Result.ok() : Result.error("删除失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
