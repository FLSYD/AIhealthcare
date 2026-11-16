package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.dao.NotificationDao;
import com.aaa.aihealthcare.service.NotificationService;
import com.aaa.aihealthcare.vo.NotificationVO;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class NotificationServiceImpl implements NotificationService {
    @Autowired
    private NotificationDao notificationDao;

    @Override
    public PageInfo<NotificationVO> page(Long userId, boolean unreadOnly, Integer pageNum, Integer pageSize) {
        requireUser(userId);
        try {
            PageUtils.startPage(pageNum, pageSize);
            List<NotificationVO> rows = notificationDao.selectByUser(userId, unreadOnly);
            return new PageInfo<>(rows);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public long unreadCount(Long userId) {
        requireUser(userId);
        return notificationDao.countUnread(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long id) {
        requireUser(userId);
        if (id == null || id < 1) {
            throw new IllegalArgumentException("消息ID必须为正整数");
        }
        if (!notificationDao.existsForUser(id, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "消息不存在或无权访问");
        }
        // 重复标记已读幂等；SQL始终保留接收人条件。
        notificationDao.markRead(id, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(Long userId) {
        requireUser(userId);
        return notificationDao.markAllRead(userId);
    }

    // 业务通知参与原业务事务，业务失败时一并撤销，不能提前生成成功消息。
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void scheduleChanged(Long doctorId, Long scheduleId, String content) {
        notificationDao.insertForDoctor(doctorId, "schedule_changed", "排班变更", content, "schedule", scheduleId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void appointmentCancelled(Long appointmentId, Long patientUserId) {
        notificationDao.insertForAppointmentDoctor(appointmentId, "appointment_cancelled", "预约取消", "一条预约已取消，请查看我的预约。");
        notificationDao.insertForUser(patientUserId, "appointment_cancelled", "预约取消", "您的预约已取消，退款结果以账户或支付记录为准。", "appointment", appointmentId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void consultationStarted(Long appointmentId, Long patientUserId) {
        notificationDao.insertForUser(patientUserId, "consultation_started", "开始就诊", "医生已开始本次接诊。", "appointment", appointmentId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void prescriptionCreated(Long appointmentId, Long patientUserId) {
        notificationDao.insertForUser(patientUserId, "prescription_created", "处方待缴费", "医生已开具处方，请查看处方并缴纳药费。", "appointment", appointmentId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void appointmentCompleted(Long appointmentId, Long patientUserId, boolean dispensed) {
        notificationDao.insertForUser(patientUserId, dispensed ? "prescription_dispensed" : "appointment_completed",
                dispensed ? "取药完成" : "就诊完成", dispensed ? "本次处方已发药，就诊已完成。" : "本次预约已标记完成。", "appointment", appointmentId);
        if (dispensed) {
            notificationDao.insertForAppointmentDoctor(appointmentId, "prescription_dispensed", "处方已发药", "本次预约已完成发药。");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void prescriptionDispensed(Long prescriptionId) {
        notificationDao.insertForPharmacists(prescriptionId, "prescription_dispensed", "配药完成", "处方已完成发药，请勿重复操作，可查看已发药记录。");
    }

    private void requireUser(Long userId) {
        if (userId == null || userId < 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
    }
}
