package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.vo.AppointmentVO;
import com.aaa.aihealthcare.vo.DoctorVO;
import com.aaa.aihealthcare.vo.ScheduleVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

public interface DoctorWorkspaceDao {

    DoctorVO selectDoctor(@Param("doctorId") Long doctorId);

    Long countTodayAppointments(@Param("doctorId") Long doctorId);

    Long countPendingAppointments(@Param("doctorId") Long doctorId);

    Long countCompletedAppointments(@Param("doctorId") Long doctorId);

    List<ScheduleVO> selectSchedules(@Param("doctorId") Long doctorId,
                                     @Param("startDate") LocalDate startDate,
                                     @Param("endDate") LocalDate endDate);

    ScheduleVO selectSchedule(@Param("doctorId") Long doctorId, @Param("id") Long id);

    List<AppointmentVO> selectAppointments(@Param("doctorId") Long doctorId,
                                           @Param("keyword") String keyword,
                                           @Param("status") String status,
                                           @Param("scheduleDate") String scheduleDate);

    AppointmentVO selectAppointment(@Param("doctorId") Long doctorId, @Param("id") Long id);
}
