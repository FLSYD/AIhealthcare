package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.service.AppointmentService;
import com.aaa.aihealthcare.vo.AppointmentVO;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 预约管理接口，统一前缀 /api/appointment。
 */
@RestController
@RequireRoles("ADMIN")
@RequestMapping("/api/appointment")
public class AppointmentController {

    @Autowired
    private AppointmentService appointmentService;

    @GetMapping("/page")
    public Result page(@RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String scheduleDate,
                       @RequestParam(defaultValue = "1") Integer pageNum,
                       @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<AppointmentVO> pageInfo =
                appointmentService.page(keyword, status, scheduleDate, pageNum, pageSize);
        return Result.ok(pageInfo);
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        AppointmentVO appointment = appointmentService.getDetailById(id);
        if (appointment == null) {
            return Result.error("预约不存在");
        }
        return Result.ok(appointment);
    }

    /**
     * 取消预约：释放号源，未缴费处方一并作废。
     */
    @PutMapping("/{id}/cancel")
    @OperationLog(module = "预约管理", action = "取消预约")
    public Result cancel(@PathVariable Long id) {
        try {
            return appointmentService.cancel(id) ? Result.ok() : Result.error("取消失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 标记完成。
     */
    @PutMapping("/{id}/complete")
    @OperationLog(module = "预约管理", action = "完成预约")
    public Result complete(@PathVariable Long id) {
        try {
            return appointmentService.complete(id) ? Result.ok() : Result.error("操作失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 标记取药：处方需已支付，发药后预约转为已完成。
     */
    @PutMapping("/prescription/{prescriptionId}/dispense")
    @OperationLog(module = "预约管理", action = "处方发药")
    public Result dispense(@PathVariable Long prescriptionId) {
        try {
            return appointmentService.dispense(prescriptionId) ? Result.ok() : Result.error("操作失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
