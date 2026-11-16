package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.service.DoctorConsultationService;
import com.aaa.aihealthcare.service.PharmacyService;
import com.aaa.aihealthcare.vo.PrescriptionVO;
import com.github.pagehelper.PageInfo;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 配药工作台：PHARMACIST 专用。 */
@RestController
@RequestMapping("/api/pharmacy")
@RequireRoles({"PHARMACIST"})
public class PharmacyController {

    private final PharmacyService pharmacyService;
    private final DoctorConsultationService doctorConsultationService;

    public PharmacyController(PharmacyService pharmacyService,
                              DoctorConsultationService doctorConsultationService) {
        this.pharmacyService = pharmacyService;
        this.doctorConsultationService = doctorConsultationService;
    }

    /** 处方列表：status=paid（待发药，默认）/ dispensed（已发药）。 */
    @GetMapping("/prescriptions")
    public Result prescriptions(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String keyword,
                                @RequestParam(defaultValue = "1") Integer pageNum,
                                @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            PageInfo<PrescriptionVO> page = pharmacyService.page(status, keyword, pageNum, pageSize);
            return Result.ok(page);
        } catch (IllegalArgumentException exception) {
            return Result.error(exception.getMessage());
        }
    }

    /** 处方详情，含药品明细。 */
    @GetMapping("/prescriptions/{id}")
    public Result detail(@PathVariable Long id) {
        try {
            return Result.ok(pharmacyService.detail(id));
        } catch (IllegalArgumentException exception) {
            return Result.error(exception.getMessage());
        }
    }

    /** 发药：仅接受已付款处方，成功后预约状态置为已完成。 */
    @PostMapping("/prescriptions/{id}/dispense")
    @OperationLog(module = "配药管理", action = "发药")
    public Result dispense(@PathVariable Long id) {
        try {
            doctorConsultationService.dispenseAsPharmacist(id);
            return Result.ok();
        } catch (IllegalArgumentException exception) {
            return Result.error(exception.getMessage());
        } catch (DataAccessException exception) {
            return Result.error("发药失败，请稍后重试");
        }
    }
}
