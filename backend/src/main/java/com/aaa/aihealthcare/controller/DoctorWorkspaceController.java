package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.DoctorProfileService;
import com.aaa.aihealthcare.service.DoctorWorkspaceService;
import com.aaa.aihealthcare.vo.AppointmentVO;
import com.aaa.aihealthcare.vo.IntroUpdateVO;
import com.aaa.aihealthcare.vo.ScheduleVO;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 医生本人工作台接口。
 */
@RestController
@RequestMapping("/api/doctor-workspace")
@RequireRoles("DOCTOR")
public class DoctorWorkspaceController {

    @Autowired
    private DoctorWorkspaceService doctorWorkspaceService;

    @Autowired
    private DoctorProfileService doctorProfileService;

    @GetMapping("/overview")
    public Result overview(@RequestAttribute("loginUser") SysUser currentUser) {
        return Result.ok(doctorWorkspaceService.getOverview(currentUser));
    }

    @GetMapping("/profile")
    public Result profile(@RequestAttribute("loginUser") SysUser currentUser) {
        return Result.ok(doctorWorkspaceService.getProfile(currentUser));
    }

    @PutMapping("/profile/intro")
    public Result updateIntro(@RequestAttribute("loginUser") SysUser currentUser,
                              @RequestBody IntroUpdateVO request) {
        try {
            return Result.ok(doctorWorkspaceService.updateIntro(currentUser, request));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(exception.getMessage());
        }
    }

    @PostMapping(value = "/profile/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result updateAvatar(@RequestAttribute("loginUser") SysUser currentUser,
                               @RequestParam("file") MultipartFile file) {
        try {
            return Result.ok(doctorProfileService.updateForDoctor(currentUser, file));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Result.error(exception.getMessage());
        }
    }

    @GetMapping("/schedules")
    public Result schedules(@RequestAttribute("loginUser") SysUser currentUser,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                            @RequestParam(defaultValue = "1") Integer pageNum,
                            @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<ScheduleVO> pageInfo = doctorWorkspaceService.pageSchedules(
                currentUser, startDate, endDate, pageNum, pageSize);
        return Result.ok(pageInfo);
    }

    @GetMapping("/schedules/{id}")
    public Result scheduleDetail(@RequestAttribute("loginUser") SysUser currentUser,
                                 @PathVariable Long id) {
        return Result.ok(doctorWorkspaceService.getSchedule(currentUser, id));
    }

    @GetMapping("/appointments")
    public Result appointments(@RequestAttribute("loginUser") SysUser currentUser,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String scheduleDate,
                               @RequestParam(defaultValue = "1") Integer pageNum,
                               @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<AppointmentVO> pageInfo = doctorWorkspaceService.pageAppointments(
                currentUser, keyword, status, scheduleDate, pageNum, pageSize);
        return Result.ok(pageInfo);
    }

    @GetMapping("/appointments/{id}")
    public Result appointmentDetail(@RequestAttribute("loginUser") SysUser currentUser,
                                    @PathVariable Long id) {
        return Result.ok(doctorWorkspaceService.getAppointment(currentUser, id));
    }
}
