package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.DoctorConsultationService;
import com.aaa.aihealthcare.vo.ConsultationRecordRequest;
import com.aaa.aihealthcare.vo.PrescriptionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医生本人诊疗操作接口。
 */
@RestController
@RequestMapping("/api/doctor-workspace/consultations")
@RequireRoles("DOCTOR")
public class DoctorConsultationController {

    @Autowired
    private DoctorConsultationService doctorConsultationService;

    @GetMapping("/{id}")
    public Result detail(@RequestAttribute("loginUser") SysUser currentUser,
                         @PathVariable Long id) {
        return Result.ok(doctorConsultationService.getDetail(currentUser, id));
    }

    @PutMapping("/{id}/start")
    @OperationLog(module = "医生诊疗", action = "开始接诊")
    public Result start(@RequestAttribute("loginUser") SysUser currentUser,
                        @PathVariable Long id) {
        doctorConsultationService.start(currentUser, id);
        return Result.ok();
    }

    @PutMapping("/{id}/record")
    @OperationLog(module = "医生诊疗", action = "保存病历")
    public Result saveRecord(@RequestAttribute("loginUser") SysUser currentUser,
                             @PathVariable Long id,
                             @RequestBody ConsultationRecordRequest request) {
        return Result.ok(doctorConsultationService.saveRecord(currentUser, id, request));
    }

    @PostMapping("/{id}/prescription")
    @OperationLog(module = "医生诊疗", action = "开具处方")
    public Result prescribe(@RequestAttribute("loginUser") SysUser currentUser,
                            @PathVariable Long id,
                            @RequestBody PrescriptionRequest request) {
        return Result.ok(doctorConsultationService.prescribe(currentUser, id, request));
    }

    @PutMapping("/{id}/complete")
    @OperationLog(module = "医生诊疗", action = "完成接诊")
    public Result complete(@RequestAttribute("loginUser") SysUser currentUser,
                           @PathVariable Long id) {
        doctorConsultationService.complete(currentUser, id);
        return Result.ok();
    }

    @PutMapping("/{id}/dispense")
    @OperationLog(module = "医生诊疗", action = "发药")
    public Result dispense(@RequestAttribute("loginUser") SysUser currentUser,
                           @PathVariable Long id) {
        doctorConsultationService.dispense(currentUser, id);
        return Result.ok();
    }
}

