package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.AnonymousAccess;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.service.PatientQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名可访问的患者挂号目录。
 */
@RestController
@RequireRoles("USER")
@RequestMapping("/api")
public class PatientCatalogController {
    @Autowired
    private PatientQueryService patientQueryService;

    @AnonymousAccess
    @GetMapping("/departments")
    public Result departments() {
        return Result.ok(patientQueryService.listDepartments());
    }

    @AnonymousAccess
    @GetMapping("/doctors")
    public Result doctors(@RequestParam(required = false) Long departmentId,
                          @RequestParam(required = false) String keyword,
                          @RequestParam(defaultValue = "false") Boolean hasAvailableSlot) {
        return Result.ok(patientQueryService.listDoctors(
                departmentId, keyword, hasAvailableSlot));
    }

    @AnonymousAccess
    @GetMapping("/doctors/{id}")
    public Result doctor(@PathVariable Long id) {
        return Result.ok(patientQueryService.getDoctor(id));
    }

    @AnonymousAccess
    @GetMapping("/schedules")
    public Result schedules(@RequestParam(required = false) Long doctorId,
                            @RequestParam(required = false) String scheduleDate) {
        return Result.ok(patientQueryService.listSchedules(doctorId, scheduleDate));
    }
}
