package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.Schedule;
import com.aaa.aihealthcare.vo.ScheduleVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

public interface ScheduleDao extends BaseMapper<Schedule> {

    List<ScheduleVO> selectPageWithDoctor(@Param("doctorId") Long doctorId,
                                          @Param("startDate") LocalDate startDate,
                                          @Param("endDate") LocalDate endDate);

    Schedule selectByIdForUpdate(@Param("id") Long id);

    List<Long> selectAppointmentIdsByScheduleIdForUpdate(@Param("scheduleId") Long scheduleId);

    long countUnfinishedAppointments(@Param("scheduleId") Long scheduleId);

    int updateWithoutBookedCount(@Param("schedule") Schedule schedule);
}
