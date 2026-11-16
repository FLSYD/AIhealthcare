package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.common.AppointmentStatus;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.DoctorWorkspaceDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.DoctorWorkspaceService;
import com.aaa.aihealthcare.vo.AppointmentVO;
import com.aaa.aihealthcare.vo.DoctorVO;
import com.aaa.aihealthcare.vo.DoctorWorkbenchVO;
import com.aaa.aihealthcare.vo.IntroUpdateVO;
import com.aaa.aihealthcare.vo.ScheduleVO;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DoctorWorkspaceServiceImpl implements DoctorWorkspaceService {

    private static final String INVALID_DOCTOR_MESSAGE = "账号未关联有效医生，请联系管理员";

    @Autowired
    private DoctorDao doctorDao;

    @Autowired
    private DoctorWorkspaceDao doctorWorkspaceDao;

    @Autowired
    private SysUserDao sysUserDao;

    @Override
    public DoctorWorkbenchVO getOverview(SysUser currentUser) {
        Long doctorId = requireDoctorId(currentUser);
        DoctorVO doctor = doctorWorkspaceDao.selectDoctor(doctorId);
        if (doctor == null) {
            throw invalidDoctor();
        }

        DoctorWorkbenchVO overview = new DoctorWorkbenchVO();
        overview.setDoctor(doctor);
        overview.setTodayAppointments(doctorWorkspaceDao.countTodayAppointments(doctorId));
        overview.setPendingAppointments(doctorWorkspaceDao.countPendingAppointments(doctorId));
        overview.setCompletedAppointments(doctorWorkspaceDao.countCompletedAppointments(doctorId));
        return overview;
    }

    @Override
    public DoctorVO getProfile(SysUser currentUser) {
        Long doctorId = requireDoctorId(currentUser);
        DoctorVO profile = doctorWorkspaceDao.selectDoctor(doctorId);
        if (profile == null) {
            throw invalidDoctor();
        }
        return profile;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DoctorVO updateIntro(SysUser currentUser, IntroUpdateVO request) {
        Long doctorId = requireBoundDoctorId(currentUser);
        String intro = validateIntro(request);
        sysUserDao.lockActiveAdminIds();
        Doctor current = doctorDao.selectByIdForUpdate(doctorId);
        if (!isActiveDoctor(current)) {
            throw invalidDoctor();
        }

        if (doctorDao.updateIntro(doctorId, intro, LocalDateTime.now()) != 1) {
            throw new IllegalStateException("医生简介保存失败，请刷新后重试");
        }

        DoctorVO profile = doctorWorkspaceDao.selectDoctor(doctorId);
        if (profile == null) {
            throw invalidDoctor();
        }
        return profile;
    }

    @Override
    public PageInfo<ScheduleVO> pageSchedules(SysUser currentUser, LocalDate startDate, LocalDate endDate,
                                              Integer pageNum, Integer pageSize) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        Long doctorId = requireDoctorId(currentUser);
        try {
            PageUtils.startPage(pageNum, pageSize);
            List<ScheduleVO> list = doctorWorkspaceDao.selectSchedules(doctorId, startDate, endDate);
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public ScheduleVO getSchedule(SysUser currentUser, Long id) {
        Long doctorId = requireDoctorId(currentUser);
        ScheduleVO schedule = doctorWorkspaceDao.selectSchedule(doctorId, id);
        if (schedule == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "排班不存在或无权访问");
        }
        return schedule;
    }

    @Override
    public PageInfo<AppointmentVO> pageAppointments(SysUser currentUser, String keyword, String status,
                                                    String scheduleDate, Integer pageNum, Integer pageSize) {
        AppointmentStatus.validateFilter(status);
        AppointmentStatus.validateDate(scheduleDate);
        Long doctorId = requireDoctorId(currentUser);
        try {
            PageUtils.startPage(pageNum, pageSize);
            List<AppointmentVO> list = doctorWorkspaceDao.selectAppointments(
                    doctorId, keyword, status, scheduleDate);
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public AppointmentVO getAppointment(SysUser currentUser, Long id) {
        Long doctorId = requireDoctorId(currentUser);
        AppointmentVO appointment = doctorWorkspaceDao.selectAppointment(doctorId, id);
        if (appointment == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "预约不存在或无权访问");
        }
        return appointment;
    }

    private Long requireDoctorId(SysUser currentUser) {
        Long doctorId = requireBoundDoctorId(currentUser);
        Doctor doctor = doctorDao.selectById(doctorId);
        if (!isActiveDoctor(doctor)) {
            throw invalidDoctor();
        }
        return doctorId;
    }

    private Long requireBoundDoctorId(SysUser currentUser) {
        if (currentUser == null
                || !"DOCTOR".equals(currentUser.getRole())
                || !Integer.valueOf(1).equals(currentUser.getStatus())
                || !Integer.valueOf(0).equals(currentUser.getDeleted())
                || currentUser.getDoctorId() == null
                || currentUser.getDoctorId() < 1) {
            throw invalidDoctor();
        }
        return currentUser.getDoctorId();
    }

    private boolean isActiveDoctor(Doctor doctor) {
        return doctor != null && Integer.valueOf(1).equals(doctor.getStatus());
    }

    private String validateIntro(IntroUpdateVO request) {
        if (request == null) {
            throw new IllegalArgumentException("简介参数不能为空");
        }
        String intro = request.getIntro();
        if (intro != null && intro.codePointCount(0, intro.length()) > 1000) {
            throw new IllegalArgumentException("医生简介长度不能超过 1000");
        }
        return intro;
    }

    private ResponseStatusException invalidDoctor() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, INVALID_DOCTOR_MESSAGE);
    }
}
