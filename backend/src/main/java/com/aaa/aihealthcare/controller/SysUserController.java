package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.UserValidation;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.SysUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

@RestController
@RequireRoles("ADMIN")
@RequestMapping("/api/sysUser")
public class SysUserController {
    @Autowired
    private SysUserService sysUserService;

    @GetMapping("/list")
    public Result list(@RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize, SysUser sysUser) {
        // 全项目统一分页入参为 pageNum；page 仅作为旧调用方的兼容别名保留。
        int effectivePageNum = pageNum != null ? pageNum : (page != null ? page : 1);
        if (effectivePageNum < 1) {
            return Result.error("页码必须为正整数");
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            return Result.error("每页条数必须在1到100之间");
        }
        if (sysUser.getKeyword() != null) {
            sysUser.setKeyword(sysUser.getKeyword().trim());
        }
        return Result.ok(sysUserService.queryByCondition(effectivePageNum, pageSize, sysUser));
    }

    @GetMapping("/getById")
    public Result getById(@RequestParam("id") Long id) {
        if (id == null || id < 1) {
            return Result.error("用户ID必须为正整数");
        }
        SysUser user = sysUserService.getById(id);
        return user == null ? Result.error("用户不存在") : Result.ok(user);
    }

    @PostMapping("/add")
    @OperationLog(module = "用户管理", action = "新增用户")
    public Result add(@RequestBody SysUser request) {
        if (request == null || request.getUsername() == null) {
            return Result.error("用户名不能为空");
        }
        String username = UserValidation.normalizeUsername(request.getUsername());
        if (username.isEmpty()) {
            return Result.error("用户名不能为空");
        }
        if (username.length() < 3 || username.length() > 50) {
            return Result.error("用户名长度必须为3到50个字符");
        }
        String error = UserValidation.password(request.getPassword(), 3);
        if (error == null) {
            error = UserValidation.profile(request);
        }
        if (error != null) {
            return Result.error(error);
        }
        if (request.getRole() == null || !Set.of("ADMIN", "DOCTOR").contains(request.getRole())) {
            return Result.error("新增用户角色只能为管理员或医生");
        }
        if (!validStatus(request.getStatus())) {
            return Result.error("状态只能为0或1");
        }
        if (sysUserService.selectByUsername(username) != null) {
            return Result.error("用户名已存在");
        }
        SysUser user = editableFields(request);
        user.setUsername(username);
        user.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        try {
            return sysUserService.insert(user) > 0 ? Result.ok(user.getId()) : Result.error("新增用户失败");
        } catch (DuplicateKeyException exception) {
            return Result.error("用户名已存在");
        }
    }

    @PutMapping("/update")
    @OperationLog(module = "用户管理", action = "修改用户")
    public Result update(@RequestAttribute("loginUserId") Long loginUserId, @RequestBody SysUser request) {
        if (request == null || request.getId() == null || request.getId() < 1) {
            return Result.error("用户ID必须为正整数");
        }
        String error = UserValidation.profile(request);
        if (error == null && request.getPassword() != null && !request.getPassword().isBlank()) {
            error = UserValidation.password(request.getPassword(), 3);
        }
        if (error != null) {
            return Result.error(error);
        }
        if (request.getRole() != null && !Set.of("ADMIN", "DOCTOR", "USER", "PHARMACIST").contains(request.getRole())) {
            return Result.error("角色只能为ADMIN、DOCTOR、PHARMACIST或USER");
        }
        if (!validStatus(request.getStatus())) {
            return Result.error("状态只能为0或1");
        }
        if (loginUserId.equals(request.getId())
                && (Integer.valueOf(0).equals(request.getStatus())
                || (request.getRole() != null && !"ADMIN".equals(request.getRole())))) {
            return Result.error("不能禁用自己或取消自己的管理员身份");
        }
        SysUser user = editableFields(request);
        user.setId(request.getId());
        return sysUserService.update(user) > 0 ? Result.ok() : Result.error("用户不存在或修改失败");
    }

    @DeleteMapping("/delete")
    @OperationLog(module = "用户管理", action = "删除用户")
    public Result delete(@RequestAttribute("loginUserId") Long loginUserId, @RequestParam("id") Long id) {
        if (id == null || id < 1) {
            return Result.error("用户ID必须为正整数");
        }
        if (loginUserId.equals(id)) {
            return Result.error("不能删除当前登录用户");
        }
        try {
            return sysUserService.delete(id) > 0 ? Result.ok() : Result.error("用户不存在，无法删除");
        } catch (DataIntegrityViolationException exception) {
            return Result.error("用户存在关联数据，无法删除");
        }
    }

    @DeleteMapping("/batchDelete")
    @OperationLog(module = "用户管理", action = "批量删除用户")
    public Result batchDelete(@RequestAttribute("loginUserId") Long loginUserId, @RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            return Result.error("批量删除需提交1到100个用户ID");
        }
        if (ids.stream().anyMatch(id -> id == null || id < 1)) {
            return Result.error("用户ID必须为正整数");
        }
        if (ids.contains(loginUserId)) {
            return Result.error("不能删除当前登录用户");
        }
        try {
            return Result.ok(sysUserService.batchDelete(ids));
        } catch (IllegalArgumentException exception) {
            return Result.error(exception.getMessage());
        } catch (DataIntegrityViolationException exception) {
            return Result.error("部分用户存在关联数据，批量删除已取消");
        }
    }

    @ExceptionHandler(DataAccessException.class)
    public Result handleDataAccessException(DataAccessException exception) {
        return Result.error("用户数据操作失败，请检查关联数据");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result handleInvalidBinding(IllegalArgumentException exception) {
        return Result.error(exception.getMessage());
    }

    private boolean validStatus(Integer status) {
        return status == null || status == 0 || status == 1;
    }

    // 只复制管理员表单允许写入的字段，忽略用户名修改、余额和平台身份等额外属性。
    private SysUser editableFields(SysUser request) {
        SysUser user = new SysUser();
        user.setPassword(request.getPassword());
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setRole(request.getRole());
        user.setStatus(request.getStatus());
        user.setDoctorId(request.getDoctorId());
        return user;
    }
}