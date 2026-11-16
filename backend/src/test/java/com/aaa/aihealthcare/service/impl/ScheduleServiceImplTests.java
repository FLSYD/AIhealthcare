package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.ScheduleDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.Schedule;
import com.aaa.aihealthcare.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceImplTests {

    @Mock
    private NotificationService notificationService;

    @Mock
    private ScheduleDao scheduleDao;

    @Mock
    private DoctorDao doctorDao;

    @Mock
    private SysUserDao sysUserDao;

    @InjectMocks
    private ScheduleServiceImpl service;

    @Test
    void updateLocksAppointmentsBeforeScheduleAndDoesNotWriteBookedCount() {
        Schedule current = schedule(11L, 2);
        Schedule request = schedule(11L, null);
        Doctor doctor = activeDoctor(2L);
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(scheduleDao.selectAppointmentIdsByScheduleIdForUpdate(11L)).thenReturn(List.of(7L));
        when(scheduleDao.selectByIdForUpdate(11L)).thenReturn(current);
        when(doctorDao.selectByIdForUpdate(2L)).thenReturn(doctor);
        when(doctorDao.selectEnabledDepartmentForUpdate(2L)).thenReturn(clinicalDepartment());
        when(scheduleDao.selectCount(any())).thenReturn(0L);
        when(scheduleDao.updateWithoutBookedCount(request)).thenReturn(1);

        assertTrue(service.update(request));

        InOrder order = inOrder(sysUserDao, scheduleDao, doctorDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(scheduleDao).selectAppointmentIdsByScheduleIdForUpdate(11L);
        order.verify(scheduleDao).selectByIdForUpdate(11L);
        order.verify(doctorDao).selectByIdForUpdate(2L);
        order.verify(scheduleDao).updateWithoutBookedCount(request);
    }

    @Test
    void updateRejectsClientBookedCountChange() {
        Schedule current = schedule(11L, 2);
        Schedule request = schedule(11L, 1);
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(scheduleDao.selectAppointmentIdsByScheduleIdForUpdate(11L)).thenReturn(List.of());
        when(scheduleDao.selectByIdForUpdate(11L)).thenReturn(current);
        when(doctorDao.selectByIdForUpdate(2L)).thenReturn(activeDoctor(2L));
        when(doctorDao.selectEnabledDepartmentForUpdate(2L)).thenReturn(clinicalDepartment());
        when(scheduleDao.selectCount(any())).thenReturn(0L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.update(request));

        assertEquals("已预约数由系统维护，不能直接修改", exception.getMessage());
        verify(scheduleDao, never()).updateWithoutBookedCount(any());
    }

    @Test
    void deleteRejectsAnyAppointmentAndLocksItBeforeSchedule() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(scheduleDao.selectAppointmentIdsByScheduleIdForUpdate(11L)).thenReturn(List.of(99L));
        when(scheduleDao.selectByIdForUpdate(11L)).thenReturn(schedule(11L, 0));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.deleteById(11L));

        assertEquals("该排班已有预约记录（含已取消或已完成），无法删除", exception.getMessage());
        InOrder order = inOrder(sysUserDao, scheduleDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(scheduleDao).selectAppointmentIdsByScheduleIdForUpdate(11L);
        order.verify(scheduleDao).selectByIdForUpdate(11L);
        verify(scheduleDao, never()).deleteById(11L);
    }

    private Schedule schedule(Long id, Integer bookedCount) {
        Schedule schedule = new Schedule();
        schedule.setId(id);
        schedule.setDoctorId(2L);
        schedule.setScheduleDate(LocalDate.of(2026, 11, 12));
        schedule.setTimeSlot("09:00");
        schedule.setTotalQuota(3);
        schedule.setBookedCount(bookedCount);
        schedule.setStatus(1);
        return schedule;
    }

    private Doctor activeDoctor(Long id) {
        Doctor doctor = new Doctor();
        doctor.setId(id);
        doctor.setStatus(1);
        doctor.setRoleType("DOCTOR");
        return doctor;
    }

    private com.aaa.aihealthcare.entity.Department clinicalDepartment() {
        var department = new com.aaa.aihealthcare.entity.Department();
        department.setType("CLINICAL"); department.setStatus(1); return department;
    }
}
