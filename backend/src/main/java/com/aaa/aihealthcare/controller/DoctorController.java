package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.service.DoctorProfileService;
import com.aaa.aihealthcare.service.DoctorService;
import com.aaa.aihealthcare.vo.DoctorVO;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequireRoles("ADMIN")
@RequestMapping("/api/doctor")
public class DoctorController {

    @Autowired
    private DoctorService doctorService;

    @Autowired
    private DoctorProfileService doctorProfileService;

    @GetMapping("/page")
    public Result page(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Long departmentId,
                       @RequestParam(defaultValue = "1") Integer pageNum,
                       @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<DoctorVO> pageInfo = doctorService.page(keyword, departmentId, pageNum, pageSize);
        return Result.ok(pageInfo);
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        Doctor doctor = doctorService.getById(id);
        if (doctor == null) {
            return Result.error("医生不存在");
        }
        return Result.ok(doctor);
    }

    @PostMapping
    @OperationLog(module = "医生管理", action = "新增医生")
    public Result save(@RequestBody Doctor doctor) {
        try {
            return Result.ok(doctorService.createWithAccount(doctor));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (IllegalStateException | DataAccessException e) {
            return Result.error("新增医生或登录账号失败，请稍后重试");
        }
    }

    @PutMapping
    @OperationLog(module = "医生管理", action = "修改医生")
    public Result update(@RequestBody Doctor doctor) {
        try {
            return doctorService.update(doctor) ? Result.ok() : Result.error("修改失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @OperationLog(module = "医生管理", action = "删除医生")
    public Result deleteById(@PathVariable Long id) {
        return doctorService.deleteById(id) ? Result.ok() : Result.error("删除失败");
    }

    @PutMapping("/{id}/status/{status}")
    @OperationLog(module = "医生管理", action = "修改医生状态")
    public Result updateStatus(@PathVariable Long id, @PathVariable Integer status) {
        return doctorService.updateStatus(id, status) ? Result.ok() : Result.error("状态修改失败");
    }

    @PostMapping(value = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @OperationLog(module = "医生管理", action = "上传医生头像")
    public Result updateAvatar(@PathVariable Long id,
                               @RequestParam("file") MultipartFile file) {
        try {
            return Result.ok(doctorProfileService.updateByAdmin(id, file));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(exception.getMessage());
        }
    }
}
