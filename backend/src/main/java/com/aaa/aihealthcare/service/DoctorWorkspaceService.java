package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.vo.AppointmentVO;
import com.aaa.aihealthcare.vo.DoctorVO;
import com.aaa.aihealthcare.vo.DoctorWorkbenchVO;
import com.aaa.aihealthcare.vo.IntroUpdateVO;
import com.aaa.aihealthcare.vo.ScheduleVO;
import com.github.pagehelper.PageInfo;

import java.time.LocalDate;

public interface DoctorWorkspaceService {

    DoctorWorkbenchVO getOverview(SysUser currentUser);

    DoctorVO getProfile(SysUser currentUser);

    DoctorVO updateIntro(SysUser currentUser, IntroUpdateVO request);

    PageInfo<ScheduleVO> pageSchedules(SysUser currentUser, LocalDate startDate, LocalDate endDate,
                                       Integer pageNum, Integer pageSize);

    ScheduleVO getSchedule(SysUser currentUser, Long id);

    PageInfo<AppointmentVO> pageAppointments(SysUser currentUser, String keyword, String status,
                                             String scheduleDate, Integer pageNum, Integer pageSize);

    AppointmentVO getAppointment(SysUser currentUser, Long id);
}
