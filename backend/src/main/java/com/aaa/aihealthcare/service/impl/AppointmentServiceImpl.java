package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AppointmentStatus;
import com.aaa.aihealthcare.common.AppointmentPaymentWindow;
import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.dao.AppointmentDao;
import com.aaa.aihealthcare.dao.PrescriptionDao;
import com.aaa.aihealthcare.entity.Appointment;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.payment.NativePaymentGuard;
import com.aaa.aihealthcare.service.AppointmentService;
import com.aaa.aihealthcare.service.DoctorConsultationService;
import com.aaa.aihealthcare.service.NotificationService;
import com.aaa.aihealthcare.dao.DoctorConsultationDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.vo.AppointmentVO;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Service
public class AppointmentServiceImpl implements AppointmentService {

    @Autowired
    private AppointmentDao appointmentDao;

    @Autowired
    private PrescriptionDao prescriptionDao;

    @Autowired
    private DoctorConsultationService doctorConsultationService;

    @Autowired
    private DoctorConsultationDao doctorConsultationDao;

    @Autowired
    private SysUserDao sysUserDao;

    @Autowired
    private NotificationService notificationService;

    @Autowired(required=false)
    private NativePaymentGuard nativePaymentGuard;

    @Override
    public PageInfo<AppointmentVO> page(String keyword, String status, String scheduleDate,
                                        Integer pageNum, Integer pageSize) {
        AppointmentStatus.validateFilter(status);
        AppointmentStatus.validateDate(scheduleDate);
        try {
            PageUtils.startPage(pageNum, pageSize);
            List<AppointmentVO> list = appointmentDao.selectPageWithDetail(keyword, status, scheduleDate);
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public AppointmentVO getDetailById(Long id) {
        return appointmentDao.selectDetailById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(Long id) {
        // 退款将锁定患者余额；先沿用管理写锁，避免与账号及排班维护形成反向锁序。
        sysUserDao.lockActiveAdminIds();
        Appointment appointment = requireAppointment(id);
        return cancelLocked(appointment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean expireUnpaid(Long id) {
        // 与付款、手动取消保持相同锁序，取锁后重新检查，避免扫描结果过时。
        sysUserDao.lockActiveAdminIds();
        Appointment appointment = appointmentDao.selectByIdForUpdate(id);
        if (appointment == null || !AppointmentStatus.UNPAID.equals(appointment.getStatus())
                || appointment.getPayTime() != null
                || !AppointmentPaymentWindow.isExpired(appointment.getCreateTime(), LocalDateTime.now())) {
            return false;
        }
        if (nativePaymentGuard != null && nativePaymentGuard.hasActiveAppointment(id)) return false;
        return cancelLocked(appointment);
    }

    private boolean cancelLocked(Appointment appointment) {
        if (nativePaymentGuard != null && nativePaymentGuard.hasActiveAppointment(appointment.getId())) {
            throw new IllegalArgumentException("微信扫码支付结果待确认，请先查看或关闭支付单再取消预约");
        }
        if (AppointmentStatus.CANCELLED.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("该预约已取消，请勿重复操作");
        }
        if (!AppointmentStatus.canCancel(appointment.getStatus())) {
            throw new IllegalArgumentException("当前状态为「" + AppointmentStatus.label(appointment.getStatus())
                    + "」，不允许取消");
        }

        // 处方已支付的预约需先处理药费，避免患者已付款却被直接取消
        Prescription prescription = findPrescription(appointment.getId());
        if (prescription != null && AppointmentStatus.RX_DISPENSED.equals(prescription.getStatus())) {
            throw new IllegalArgumentException("该预约的处方已发药，不能取消");
        }
        if (prescription != null && AppointmentStatus.RX_PAID.equals(prescription.getStatus())) {
            throw new IllegalArgumentException("该预约的处方已支付，请先处理药费后再取消");
        }

        BigDecimal balanceAfter = prepareRefund(appointment);

        boolean ok = appointmentDao.updateStatusIfCurrent(appointment.getId(), appointment.getStatus(),
                AppointmentStatus.CANCELLED) == 1;
        if (!ok) {
            return false;
        }

        if (balanceAfter != null) {
            if (appointmentDao.refundBalance(appointment.getUserId(), appointment.getAmount()) != 1
                    || appointmentDao.insertRefundLog(appointment.getId(), appointment.getUserId(),
                    appointment.getAmount(), balanceAfter) != 1) {
                throw new IllegalStateException("退款失败，取消预约已撤销");
            }
        }
        if (!AppointmentStatus.UNPAID.equals(appointment.getStatus())
                && appointment.getAmount() != null && appointment.getAmount().signum() > 0
                && !"balance".equals(appointment.getPayMethod())) {
            if (appointmentDao.insertMockRefundLog(appointment) != 1) {
                throw new IllegalStateException("模拟退款记录失败，取消预约已撤销");
            }
        }

        // 释放号源：已预约数减一，且不出现负数
        releaseQuota(appointment.getScheduleId());

        // 未支付/已取消的处方随之作废
        if (prescription != null && AppointmentStatus.RX_UNPAID.equals(prescription.getStatus())) {
            Prescription rxUpdate = new Prescription();
            rxUpdate.setId(prescription.getId());
            rxUpdate.setStatus(AppointmentStatus.RX_CANCELLED);
            rxUpdate.setUpdateTime(LocalDateTime.now());
            prescriptionDao.updateById(rxUpdate);
        }
        notificationService.appointmentCancelled(appointment.getId(), appointment.getUserId());
        return true;
    }

    private BigDecimal prepareRefund(Appointment appointment) {
        if (AppointmentStatus.UNPAID.equals(appointment.getStatus())) {
            return null;
        }
        BigDecimal amount = appointment.getAmount();
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("挂号金额异常，无法取消预约");
        }
        if (amount.signum() == 0) {
            return null;
        }
        if (!"balance".equals(appointment.getPayMethod())) {
            // 只有本系统留有可信模拟付款流水的订单才可模拟退款，历史外部付款保持原保护。
            if (Set.of("wechat", "alipay").contains(java.util.Objects.toString(appointment.getPayMethod(), ""))
                    && appointmentDao.countVerifiedMockPayment(appointment) == 1) {
                return null;
            }
            throw new IllegalArgumentException("该预约使用外部支付，请先处理原路退款后再取消");
        }
        BigDecimal balance = appointmentDao.selectUserBalanceForUpdate(appointment.getUserId());
        if (balance == null || balance.signum() < 0
                || balance.add(amount).compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new IllegalArgumentException("患者账户或余额异常，无法退款");
        }
        return balance.add(amount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(Long id) {
        // 完成通知也会读取账号行，与取消及医生诊疗沿用相同的账号→预约锁序。
        sysUserDao.lockActiveAdminIds();
        Appointment appointment = requireAppointment(id);
        if (AppointmentStatus.COMPLETED.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("该预约已完成，请勿重复操作");
        }
        if (!AppointmentStatus.canComplete(appointment.getStatus())) {
            throw new IllegalArgumentException("当前状态为「" + AppointmentStatus.label(appointment.getStatus())
                    + "」，不允许标记完成");
        }
        // 处方已开但未缴费时不允许直接完成
        Prescription prescription = findPrescription(appointment.getId());
        if (prescription != null && AppointmentStatus.RX_UNPAID.equals(prescription.getStatus())) {
            throw new IllegalArgumentException("该预约的处方尚未缴费，请先完成缴费");
        }

        boolean updated = appointmentDao.updateStatusIfCurrent(appointment.getId(), appointment.getStatus(),
                AppointmentStatus.COMPLETED) == 1;
        if (updated) {
            notificationService.appointmentCompleted(appointment.getId(), appointment.getUserId(), false);
        }
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean dispense(Long prescriptionId) {
        // 管理员入口也必须经过同一付款、明细、库存及状态事务。
        doctorConsultationService.dispenseForAdmin(prescriptionId);
        return true;
    }

    private Appointment requireAppointment(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("预约id不能为空");
        }
        Appointment appointment = appointmentDao.selectByIdForUpdate(id);
        if (appointment == null) {
            throw new IllegalArgumentException("预约不存在");
        }
        return appointment;
    }

    private Prescription findPrescription(Long appointmentId) {
        List<Prescription> list = doctorConsultationDao.selectPrescriptionsForUpdate(appointmentId);
        if (list.size() > 1) {
            throw new IllegalArgumentException("预约关联多张处方，无法直接操作");
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 释放号源：booked_count 减一，最低为 0。
     */
    private void releaseQuota(Long scheduleId) {
        appointmentDao.releaseScheduleQuota(scheduleId);
    }
}
