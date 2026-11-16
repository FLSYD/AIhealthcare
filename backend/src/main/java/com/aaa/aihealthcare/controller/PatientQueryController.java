package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.service.PatientQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 患者本人预约、病历和处方只读入口。
 */
@RestController
@RequireRoles("USER")
@RequestMapping("/api")
public class PatientQueryController {
    @Autowired
    private PatientQueryService patientQueryService;

    @GetMapping("/appointments")
    public Result appointments(HttpServletRequest request,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "1") Integer current,
                               @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(patientQueryService.pageAppointments(
                currentUserId(request), status, current, size));
    }

    @GetMapping("/appointments/{id}")
    public Result appointment(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(patientQueryService.getAppointment(currentUserId(request), id));
    }

    @GetMapping("/appointments/{id}/consultation")
    public Result consultation(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(patientQueryService.getConsultation(currentUserId(request), id));
    }

    @GetMapping("/appointments/{id}/prescription")
    public Result prescriptionByAppointment(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(patientQueryService.getPrescriptionByAppointment(
                currentUserId(request), id));
    }

    @GetMapping("/prescriptions/{id}")
    public Result prescription(HttpServletRequest request, @PathVariable Long id) {
        return Result.ok(patientQueryService.getPrescription(currentUserId(request), id));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute("loginUserId");
        if (value instanceof Long userId) {
            return userId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
    }
}
