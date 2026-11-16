package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.Appointment;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.vo.*;

public interface PatientCommandService {
    Appointment create(Long userId, AppointmentCreateRequest request);
    Appointment payAppointment(Long userId, Long id, PaymentRequest request);
    Appointment cancel(Long userId, Long id);
    Appointment checkIn(Long userId, Long id);
    Prescription payPrescription(Long userId, Long id, PaymentRequest request);
    void review(Long userId, Long id, ReviewRequest request);
    java.math.BigDecimal recharge(Long userId, RechargeRequest request, String idempotencyKey);
    MiniPageVO<WalletLogVO> walletLogs(Long userId, Integer current, Integer size);
    MiniPageVO<WalletLogVO> walletLogs(Long userId, Integer current, Integer size, String type);
}
