package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AppointmentStatus;
import com.aaa.aihealthcare.dao.AppointmentDao;
import com.aaa.aihealthcare.dao.DoctorConsultationDao;
import com.aaa.aihealthcare.dao.PrescriptionDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.service.NotificationService;
import com.aaa.aihealthcare.entity.Appointment;
import com.aaa.aihealthcare.service.DoctorConsultationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceImplTests {

    @Mock
    private NotificationService notificationService;
    @Mock private AppointmentDao appointments;
    @Mock private PrescriptionDao prescriptions;
    @Mock private DoctorConsultationDao consultations;
    @Mock private SysUserDao users;
    @Mock private DoctorConsultationService clinical;
    @InjectMocks private AppointmentServiceImpl service;

    private Appointment appointment(String status, String method) {
        Appointment appointment = new Appointment();
        appointment.setId(10L);
        appointment.setUserId(20L);
        appointment.setScheduleId(30L);
        appointment.setStatus(status);
        appointment.setPayMethod(method);
        appointment.setAmount(new BigDecimal("20.00"));
        when(appointments.selectByIdForUpdate(10L)).thenReturn(appointment);
        when(consultations.selectPrescriptionsForUpdate(10L)).thenReturn(List.of());
        return appointment;
    }

    @Test
    void balanceCancellationRefundsAndRecordsTheSameAmount() {
        appointment(AppointmentStatus.CONFIRMED, "balance");
        when(appointments.selectUserBalanceForUpdate(20L)).thenReturn(new BigDecimal("80.00"));
        when(appointments.updateStatusIfCurrent(10L, "confirmed", "cancelled")).thenReturn(1);
        when(appointments.refundBalance(20L, new BigDecimal("20.00"))).thenReturn(1);
        when(appointments.insertRefundLog(10L, 20L, new BigDecimal("20.00"), new BigDecimal("100.00"))).thenReturn(1);

        assertTrue(service.cancel(10L));

        var order = inOrder(users, appointments);
        order.verify(users).lockActiveAdminIds();
        order.verify(appointments).selectByIdForUpdate(10L);
        order.verify(appointments).selectUserBalanceForUpdate(20L);
        order.verify(appointments).refundBalance(20L, new BigDecimal("20.00"));
        order.verify(appointments).insertRefundLog(10L, 20L, new BigDecimal("20.00"), new BigDecimal("100.00"));
        verify(appointments).releaseScheduleQuota(30L);
    }

    @Test
    void unpaidCancellationDoesNotRefundMoney() {
        appointment(AppointmentStatus.UNPAID, null);
        when(appointments.updateStatusIfCurrent(10L, "unpaid", "cancelled")).thenReturn(1);

        assertTrue(service.cancel(10L));

        verify(appointments, never()).refundBalance(anyLong(), any());
        verify(appointments, never()).insertRefundLog(anyLong(), anyLong(), any(), any());
    }

    @Test
    void externalPaymentIsRejectedBeforeCancellingOrChangingQuota() {
        appointment(AppointmentStatus.CONFIRMED, "wechat");

        assertThrows(IllegalArgumentException.class, () -> service.cancel(10L));

        verify(appointments, never()).updateStatusIfCurrent(anyLong(), anyString(), anyString());
        verify(appointments, never()).releaseScheduleQuota(anyLong());
    }

    @Test
    void failedRefundLogPropagatesSoTheTransactionRollsBack() {
        appointment(AppointmentStatus.CONFIRMED, "balance");
        when(appointments.selectUserBalanceForUpdate(20L)).thenReturn(new BigDecimal("80.00"));
        when(appointments.updateStatusIfCurrent(10L, "confirmed", "cancelled")).thenReturn(1);
        when(appointments.refundBalance(20L, new BigDecimal("20.00"))).thenReturn(1);
        when(appointments.insertRefundLog(anyLong(), anyLong(), any(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.cancel(10L));

        verify(appointments, never()).releaseScheduleQuota(anyLong());
    }

    @Test
    void invalidFiltersAreRejectedBeforeQueryingDatabase() {
        assertThrows(IllegalArgumentException.class, () -> service.page(null, "invalid", null, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.page(null, null, "2026-02-30", 1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.page(null, null, "2026-1-1", 1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.page(null, " ", null, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> service.page(null, null, " ", 1, 10));
        verifyNoInteractions(appointments);
    }
}
