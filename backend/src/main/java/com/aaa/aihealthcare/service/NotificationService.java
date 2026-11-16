package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.vo.NotificationVO;
import com.github.pagehelper.PageInfo;

public interface NotificationService {
    PageInfo<NotificationVO> page(Long userId, boolean unreadOnly, Integer pageNum, Integer pageSize);
    long unreadCount(Long userId);
    void markRead(Long userId, Long id);
    int markAllRead(Long userId);
    void scheduleChanged(Long doctorId, Long scheduleId, String content);
    void appointmentCancelled(Long appointmentId, Long patientUserId);
    void consultationStarted(Long appointmentId, Long patientUserId);
    void prescriptionCreated(Long appointmentId, Long patientUserId);
    void appointmentCompleted(Long appointmentId, Long patientUserId, boolean dispensed);
    void prescriptionDispensed(Long prescriptionId);
}
