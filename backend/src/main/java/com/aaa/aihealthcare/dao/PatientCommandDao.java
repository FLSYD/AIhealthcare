package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.Appointment;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.vo.BookingSlotVO;
import com.aaa.aihealthcare.vo.WalletLogVO;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PatientCommandDao {
    Appointment lockAppointment(@Param("id") Long id, @Param("userId") Long userId);
    List<Appointment> findBookingForUpdate(@Param("userId") Long userId, @Param("scheduleId") Long scheduleId,
            @Param("name") String name, @Param("phone") String phone);
    BookingSlotVO lockSlot(@Param("scheduleId") Long scheduleId);
    int reserveSlot(@Param("scheduleId") Long scheduleId);
    int insertAppointment(Appointment appointment);
    int payAppointment(@Param("id") Long id, @Param("userId") Long userId, @Param("method") String method);
    int checkIn(@Param("id") Long id, @Param("userId") Long userId);
    Prescription findPrescription(@Param("id") Long id, @Param("userId") Long userId);
    Prescription lockPrescription(@Param("id") Long id, @Param("userId") Long userId, @Param("appointmentId") Long appointmentId);
    int payPrescription(@Param("id") Long id, @Param("userId") Long userId, @Param("method") String method);
    int advanceToPickup(@Param("appointmentId") Long appointmentId, @Param("userId") Long userId);
    BigDecimal lockBalance(@Param("userId") Long userId);
    int changeBalance(@Param("userId") Long userId, @Param("delta") BigDecimal delta);
    int insertLog(@Param("userId") Long userId, @Param("type") String type, @Param("amount") BigDecimal amount,
            @Param("balanceAfter") BigDecimal balanceAfter, @Param("orderId") Long orderId,
            @Param("payMethod") String payMethod, @Param("remark") String remark);
    List<WalletLogVO> selectLogs(@Param("userId") Long userId);
    List<WalletLogVO> selectLogsByType(@Param("userId") Long userId, @Param("type") String type);
    WalletLogVO findRechargeByRemark(@Param("userId") Long userId, @Param("remark") String remark);
    long countReviews(@Param("appointmentId") Long appointmentId);
    int insertReview(@Param("appointmentId") Long appointmentId, @Param("userId") Long userId,
            @Param("doctorId") Long doctorId, @Param("rating") int rating, @Param("tags") String tags,
            @Param("comment") String comment);
}
