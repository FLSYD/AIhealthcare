package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.Appointment;
import com.aaa.aihealthcare.vo.AppointmentVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface AppointmentDao extends BaseMapper<Appointment> {

    /**
     * 分页查询预约（联查排班、医生、科室、处方），按就诊日期与时段倒序。
     */
    List<AppointmentVO> selectPageWithDetail(@Param("keyword") String keyword,
                                             @Param("status") String status,
                                             @Param("scheduleDate") String scheduleDate);

    /**
     * 单条预约详情（含关联信息）。
     */
    AppointmentVO selectDetailById(@Param("id") Long id);

    Appointment selectByIdForUpdate(@Param("id") Long id);

    List<Long> selectExpiredUnpaidIds(@Param("cutoff") LocalDateTime cutoff,
                                     @Param("afterId") Long afterId, @Param("limit") int limit);

    BigDecimal selectUserBalanceForUpdate(@Param("userId") Long userId);

    int refundBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    int insertRefundLog(@Param("appointmentId") Long appointmentId, @Param("userId") Long userId,
                        @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter);

    long countVerifiedMockPayment(@Param("appointment") Appointment appointment);

    int insertMockRefundLog(@Param("appointment") Appointment appointment);

    int updateStatusIfCurrent(@Param("id") Long id, @Param("oldStatus") String oldStatus,
                             @Param("newStatus") String newStatus);

    /**
     * 取消预约时释放号源：booked_count 减一，最低为 0。
     */
    int releaseScheduleQuota(@Param("scheduleId") Long scheduleId);
}
