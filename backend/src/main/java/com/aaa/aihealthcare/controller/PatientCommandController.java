package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.service.PatientCommandService;
import com.aaa.aihealthcare.vo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequireRoles("USER")
public class PatientCommandController {
    @Autowired private PatientCommandService service;
    @PostMapping("/appointments")
    public Result create(@RequestAttribute("loginUserId") Long userId, @RequestBody AppointmentCreateRequest request) {
        return Result.ok(service.create(userId, request));
    }
    @PostMapping("/appointments/{id}/pay")
    public Result pay(@RequestAttribute("loginUserId") Long userId, @PathVariable Long id, @RequestBody PaymentRequest request) {
        return Result.ok(service.payAppointment(userId, id, request));
    }
    @PutMapping("/appointments/{id}/cancel")
    public Result cancel(@RequestAttribute("loginUserId") Long userId, @PathVariable Long id) {
        return Result.ok(service.cancel(userId, id));
    }
    @PutMapping("/appointments/{id}/check-in")
    public Result checkIn(@RequestAttribute("loginUserId") Long userId, @PathVariable Long id) {
        return Result.ok(service.checkIn(userId, id));
    }
    @PostMapping("/prescriptions/{id}/pay")
    public Result payPrescription(@RequestAttribute("loginUserId") Long userId, @PathVariable Long id, @RequestBody PaymentRequest request) {
        return Result.ok(service.payPrescription(userId, id, request));
    }
    @PostMapping("/appointments/{id}/review")
    public Result review(@RequestAttribute("loginUserId") Long userId, @PathVariable Long id, @RequestBody ReviewRequest request) {
        service.review(userId, id, request); return Result.ok();
    }
    @PostMapping("/wallet/recharge")
    public Result recharge(@RequestAttribute("loginUserId") Long userId, @RequestBody RechargeRequest request,
                           @RequestHeader("Idempotency-Key") String key) {
        return Result.ok(service.recharge(userId, request, key));
    }
    @GetMapping("/wallet/logs")
    public Result logs(@RequestAttribute("loginUserId") Long userId, @RequestParam(defaultValue = "1") Integer current,
                       @RequestParam(defaultValue = "20") Integer size, @RequestParam(required = false) String type) {
        return Result.ok(service.walletLogs(userId, current, size, type));
    }
}
