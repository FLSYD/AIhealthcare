package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.service.DepartmentService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequireRoles("ADMIN")
@RequestMapping("/api/department")
public class DepartmentController {

    @Autowired
    private DepartmentService departmentService;

    @GetMapping("/page")
    public Result page(@RequestParam(required = false) String keyword,
                       @RequestParam(defaultValue = "1") Integer pageNum,
                       @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<Department> pageInfo = departmentService.page(keyword, pageNum, pageSize);
        return Result.ok(pageInfo);
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        Department department = departmentService.getById(id);
        if (department == null) {
            return Result.error("科室不存在");
        }
        return Result.ok(department);
    }

    @PostMapping
    @OperationLog(module = "科室管理", action = "新增科室")
    public Result save(@RequestBody Department department) {
        try {
            return departmentService.save(department) ? Result.ok() : Result.error("新增失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping
    @OperationLog(module = "科室管理", action = "修改科室")
    public Result update(@RequestBody Department department) {
        try {
            return departmentService.update(department) ? Result.ok() : Result.error("修改失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @OperationLog(module = "科室管理", action = "删除科室")
    public Result deleteById(@PathVariable Long id) {
        try {
            return departmentService.deleteById(id) ? Result.ok() : Result.error("删除失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/{id}/status/{status}")
    @OperationLog(module = "科室管理", action = "修改科室状态")
    public Result updateStatus(@PathVariable Long id, @PathVariable Integer status) {
        return departmentService.updateStatus(id, status) ? Result.ok() : Result.error("状态修改失败");
    }
}