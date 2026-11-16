package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AppointmentStatus;
import com.aaa.aihealthcare.common.AppointmentPaymentWindow;
import com.aaa.aihealthcare.common.BookingTime;
import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.dao.*;
import com.aaa.aihealthcare.entity.Appointment;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.entity.PrescriptionItem;
import com.aaa.aihealthcare.entity.Medicine;
import com.aaa.aihealthcare.payment.NativePaymentGuard;
import com.aaa.aihealthcare.service.AppointmentService;
import com.aaa.aihealthcare.service.PatientCommandService;
import com.aaa.aihealthcare.vo.*;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PatientCommandServiceImpl implements PatientCommandService {
    private static final BigDecimal FEE = new BigDecimal("20.00");
    private static final BigDecimal MAX_MONEY = new BigDecimal("99999999.99");
    private static final Set<String> METHODS = Set.of("balance", "wechat", "alipay");
    @Autowired private PatientCommandDao dao;
    @Autowired private SysUserDao users;
    @Autowired private AppointmentService appointments;
    @Autowired private NotificationDao notifications;
    @Autowired private DoctorConsultationDao consultation;
    @Autowired private MedicineDao medicines;
    @Autowired(required=false) private NativePaymentGuard nativePaymentGuard;
    @Value("${healthcare.payment.wechat-native.enabled:false}") private boolean nativeWechatEnabled;
    @Autowired private ObjectMapper mapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Appointment create(Long userId, AppointmentCreateRequest request) {
        if (request == null || request.scheduleId() == null || request.scheduleId() < 1) {
            throw new IllegalArgumentException("请选择有效排班");
        }
        String name = text(request.patientName(), 50, "就诊人姓名", true);
        String phone = text(request.patientPhone(), 20, "手机号", true);
        if (!phone.matches("1[3-9][0-9]{9}")) throw new IllegalArgumentException("请输入有效的11位手机号");
        String remark = text(request.remark(), 200, "备注", false);
        lockAccount(userId);
        List<Appointment> previous = dao.findBookingForUpdate(userId, request.scheduleId(), name, phone);
        boolean expired = false;
        for (Appointment old : previous) {
            if (AppointmentStatus.UNPAID.equals(old.getStatus())
                    && AppointmentPaymentWindow.isExpired(old.getCreateTime(), LocalDateTime.now())) {
                expired |= appointments.expireUnpaid(old.getId());
            }
        }
        if (expired) {
            previous = dao.findBookingForUpdate(userId, request.scheduleId(), name, phone);
        }
        if (!previous.isEmpty()) {
            if (previous.size() == 1 && AppointmentStatus.UNPAID.equals(previous.get(0).getStatus())) {
                return previous.get(0);
            }
            throw new IllegalArgumentException("该就诊人已预约此时段，请查看我的订单");
        }
        BookingSlotVO slot = dao.lockSlot(request.scheduleId());
        requireBookable(slot);
        if (dao.reserveSlot(slot.getId()) != 1) throw new IllegalArgumentException("号源已满或排班已停用");
        Appointment appointment = new Appointment();
        appointment.setUserId(userId); appointment.setScheduleId(slot.getId());
        appointment.setPatientName(name); appointment.setPatientPhone(phone);
        appointment.setRemark(remark); appointment.setAmount(FEE); appointment.setStatus(AppointmentStatus.UNPAID);
        if (dao.insertAppointment(appointment) != 1 || appointment.getId() == null) {
            throw new IllegalStateException("创建预约失败，号源已撤销");
        }
        notifications.insertForUser(userId, "appointment_created", "预约待支付", "预约已创建，请在订单中完成挂号付款。", "appointment", appointment.getId());
        return requireOwned(userId, appointment.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Appointment payAppointment(Long userId, Long id, PaymentRequest request) {
        String method = method(request);
        requireLocalPaymentMethod(method);
        lockAccount(userId);
        Appointment appointment = requireOwned(userId, id);
        if (nativePaymentGuard != null) nativePaymentGuard.requireNoActive("APPOINTMENT", id);
        if (!AppointmentStatus.UNPAID.equals(appointment.getStatus())) {
            if (appointment.getPayTime() != null && !AppointmentStatus.CANCELLED.equals(appointment.getStatus())) {
                requireSameMethod(method, appointment.getPayMethod());
                return appointment;
            }
            throw new IllegalArgumentException("当前预约状态不能付款");
        }
        if (appointment.getCreateTime() == null) {
            throw new IllegalArgumentException("订单创建时间异常，请联系管理员处理");
        }
        if (AppointmentPaymentWindow.isExpired(appointment.getCreateTime(), LocalDateTime.now())) {
            throw new IllegalArgumentException("订单已超过15分钟付款期限，将自动关闭，请重新预约");
        }
        BookingSlotVO slot = dao.lockSlot(appointment.getScheduleId());
        requireOpenSlot(slot);
        if (slot.getScheduleDate() == null || slot.getScheduleDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("已超过就诊日期，不能付款，请取消后重新预约");
        }
        BigDecimal amount = amount(appointment.getAmount());
        settle(userId, id, amount, method, "order_pay", "mock_order_pay", "学习模拟挂号付款");
        if (dao.payAppointment(id, userId, method) != 1) throw new IllegalStateException("预约状态已变化，付款已撤销");
        notifications.insertForUser(userId, "appointment_paid", "挂号付款成功", "挂号付款已完成，请按预约时间到院签到。", "appointment", id);
        notifications.insertForAppointmentDoctor(id, "appointment_paid", "新增待诊预约", "您有一条已付款预约，请查看我的预约。");
        return requireOwned(userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Appointment cancel(Long userId, Long id) {
        lockAccount(userId);
        Appointment appointment = requireOwned(userId, id);
        if (AppointmentStatus.CANCELLED.equals(appointment.getStatus())) return appointment;
        if (!Set.of(AppointmentStatus.UNPAID, AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED).contains(appointment.getStatus())) {
            throw new IllegalArgumentException("仅未付款或未签到的预约可以由患者取消");
        }
        if (!appointments.cancel(id)) throw new IllegalStateException("取消失败，请刷新后重试");
        return requireOwned(userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Appointment checkIn(Long userId, Long id) {
        lockAccount(userId);
        Appointment appointment = requireOwned(userId, id);
        if (AppointmentStatus.CHECKED_IN.equals(appointment.getStatus())) return appointment;
        if (!Set.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED).contains(appointment.getStatus())
                || appointment.getPayTime() == null) throw new IllegalArgumentException("只有已付款待就诊预约可以签到");
        BookingSlotVO slot = dao.lockSlot(appointment.getScheduleId());
        if (slot == null || !LocalDate.now().equals(slot.getScheduleDate())
                || !Integer.valueOf(1).equals(slot.getStatus()) || !Integer.valueOf(1).equals(slot.getDoctorStatus())
                || !Integer.valueOf(1).equals(slot.getDepartmentStatus())) {
            throw new IllegalArgumentException("仅就诊当天且正常开放的排班可以签到");
        }
        if (dao.checkIn(id, userId) != 1) throw new IllegalStateException("预约状态已变化，签到失败");
        notifications.insertForAppointmentDoctor(id, "appointment_checked_in", "患者已到院", "一条预约已签到，请查看我的预约。");
        notifications.insertForUser(userId, "appointment_checked_in", "签到成功", "您已完成签到，请等待医生接诊。", "appointment", id);
        return requireOwned(userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Prescription payPrescription(Long userId, Long id, PaymentRequest request) {
        String method = method(request);
        requireLocalPaymentMethod(method);
        lockAccount(userId);
        Prescription hint = dao.findPrescription(id, userId);
        if (hint == null) throw forbidden();
        Appointment appointment = requireOwned(userId, hint.getAppointmentId());
        Prescription prescription = dao.lockPrescription(id, userId, appointment.getId());
        if (prescription == null) throw forbidden();
        if (nativePaymentGuard != null) nativePaymentGuard.requireNoActive("PRESCRIPTION", id);
        if (Set.of(AppointmentStatus.RX_PAID, AppointmentStatus.RX_DISPENSED).contains(prescription.getStatus())) {
            requireSameMethod(method, prescription.getPayMethod()); return prescription;
        }
        if (!AppointmentStatus.RX_UNPAID.equals(prescription.getStatus())
                || !AppointmentStatus.PENDING_RX_PAY.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("当前处方或预约状态不能缴药费");
        }
        requirePrescriptionStock(id);
        settle(userId, id, amount(prescription.getTotalAmount()), method,
                "prescription_pay", "mock_rx_pay", "学习模拟药费付款");
        if (dao.payPrescription(id, userId, method) != 1 || dao.advanceToPickup(appointment.getId(), userId) != 1) {
            throw new IllegalStateException("处方状态已变化，付款已撤销");
        }
        notifications.insertForAppointmentDoctor(appointment.getId(), "prescription_paid", "处方已付款", "患者已缴药费，处方已进入配药队列。");
        notifications.insertForUser(userId, "prescription_paid", "药费付款成功", "药费已缴纳，请等待取药。", "appointment", appointment.getId());
        notifications.insertForPharmacists(id, "prescription_paid", "新增待发药处方", "患者已缴纳药费，请到配药工作台核对处方并发药。");
        return dao.lockPrescription(id, userId, appointment.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void review(Long userId, Long id, ReviewRequest request) {
        if (request == null || request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            throw new IllegalArgumentException("评分必须为1到5的整数");
        }
        String comment = text(request.comment(), 500, "评价内容", false);
        List<String> tags = request.tags() == null ? List.of() : request.tags();
        if (tags.size() > 10 || tags.stream().anyMatch(tag -> tag == null || tag.isBlank()
                || tag.codePointCount(0, tag.length()) > 20)) throw new IllegalArgumentException("评价标签不正确");
        String encoded = mapper.writeValueAsString(tags);
        if (encoded.codePointCount(0, encoded.length()) > 200) throw new IllegalArgumentException("评价标签过长");
        lockAccount(userId);
        Appointment appointment = requireOwned(userId, id);
        if (!AppointmentStatus.COMPLETED.equals(appointment.getStatus())) throw new IllegalArgumentException("仅已完成预约可以评价");
        if (dao.countReviews(id) > 0) throw new IllegalArgumentException("该预约已经评价");
        BookingSlotVO slot = dao.lockSlot(appointment.getScheduleId());
        if (slot == null || dao.insertReview(id, userId, slot.getDoctorId(), request.rating(), encoded, comment) != 1) {
            throw new IllegalStateException("评价保存失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal recharge(Long userId, RechargeRequest request, String idempotencyKey) {
        if (nativeWechatEnabled && (request == null || !"alipay".equals(request.payMethod()))) {
            throw new IllegalArgumentException("微信充值须从真实扫码入口发起；仅支付宝允许教学模拟充值");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("充值请求标识不能为空且不能超过200字符");
        }
        if (request == null || !Set.of("wechat", "alipay").contains(Objects.toString(request.payMethod(), ""))) {
            throw new IllegalArgumentException("充值仅支持模拟微信或支付宝渠道");
        }
        BigDecimal value = amount(request.amount());
        if (value.signum() <= 0 || value.compareTo(new BigDecimal("10000.00")) > 0) {
            throw new IllegalArgumentException("单次充值金额应为0.01至10000元");
        }
        lockAccount(userId);
        String remark;
        try {
            remark = "充值请求:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(idempotencyKey.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        WalletLogVO previous = dao.findRechargeByRemark(userId, remark);
        if (previous != null) {
            if (value.compareTo(previous.getAmount()) != 0 || !request.payMethod().equals(previous.getPayMethod())) {
                throw new IllegalArgumentException("同一充值标识不能用于不同充值请求");
            }
            return requireBalance(userId);
        }
        BigDecimal after = requireBalance(userId).add(value);
        if (dao.changeBalance(userId, value) != 1) throw new IllegalArgumentException("充值后余额超出允许范围");
        requireLog(dao.insertLog(userId, "recharge", value, after, null, request.payMethod(), remark));
        return after;
    }

    @Override
    public MiniPageVO<WalletLogVO> walletLogs(Long userId, Integer current, Integer size) {
        return walletLogs(userId, current, size, null);
    }

    @Override
    public MiniPageVO<WalletLogVO> walletLogs(Long userId, Integer current, Integer size, String type) {
        if (type != null && !"recharge".equals(type)) {
            throw new IllegalArgumentException("不支持的流水筛选类型");
        }
        try {
            PageUtils.startPage(current, size);
            return MiniPageVO.from(new PageInfo<>(dao.selectLogsByType(userId, type)));
        } finally { PageHelper.clearPage(); }
    }

    private void requirePrescriptionStock(Long prescriptionId) {
        List<PrescriptionItem> items = consultation.selectItemsForUpdate(prescriptionId);
        if (items.isEmpty()) throw new IllegalStateException("处方没有明细，不能缴药费");
        Set<Long> ids = new java.util.HashSet<>();
        for (PrescriptionItem item : items) {
            if (item.getMedicineId() == null || item.getQuantity() == null
                    || item.getQuantity() < 1 || item.getQuantity() > 1000
                    || !ids.add(item.getMedicineId())) {
                throw new IllegalStateException("处方明细数据异常，不能缴药费");
            }
        }
        List<Medicine> locked = consultation.selectMedicinesForUpdate(ids.stream().sorted().toList());
        if (locked.size() != ids.size()) throw new IllegalArgumentException("处方药品不存在，不能缴药费");
        for (Medicine medicine : locked) {
            int quantity = items.stream().filter(item -> medicine.getId().equals(item.getMedicineId()))
                    .findFirst().orElseThrow().getQuantity();
            if (!Integer.valueOf(1).equals(medicine.getStatus())) {
                throw new IllegalArgumentException("药品已停用，不能缴药费");
            }
            // paid 状态即库存预留；发药时才扣实物库存，并在同一事务内转为 dispensed。
            long reserved = medicines.selectPaidQuantitiesForUpdate(medicine.getId()).stream()
                    .mapToLong(Integer::longValue).sum();
            if (nativePaymentGuard != null) reserved += nativePaymentGuard.reservedQuantity(medicine.getId());
            if (medicine.getStock() == null || medicine.getStock() - reserved < quantity) {
                throw new IllegalArgumentException("药品库存不足，尚未收取药费，请联系医生或等待补货");
            }
        }
    }

    private void settle(Long userId, Long orderId, BigDecimal amount, String method,
                        String balanceType, String mockType, String mockRemark) {
        BigDecimal before = requireBalance(userId);
        if ("balance".equals(method)) {
            if (before.compareTo(amount) < 0 || dao.changeBalance(userId, amount.negate()) != 1) {
                throw new IllegalArgumentException("余额不足，请先充值");
            }
            requireLog(dao.insertLog(userId, balanceType, amount.negate(), before.subtract(amount), orderId, method, "余额付款"));
        } else {
            // 独立类型和备注标记本系统生成的模拟交易，不能把历史外部付款当作模拟单。
            requireLog(dao.insertLog(userId, mockType, amount, before, orderId, method, mockRemark));
        }
    }

    private void lockAccount(Long userId) {
        users.lockActiveAdminIds();
        requireBalance(userId);
    }

    private BigDecimal requireBalance(Long userId) {
        BigDecimal balance = dao.lockBalance(userId);
        if (balance == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不能办理患者业务");
        return balance;
    }

    private Appointment requireOwned(Long userId, Long id) {
        if (id == null || id < 1) throw forbidden();
        Appointment value = dao.lockAppointment(id, userId);
        if (value == null) throw forbidden();
        return value;
    }

    private void requireBookable(BookingSlotVO slot) {
        requireOpenSlot(slot);
        if (!BookingTime.isBookable(slot.getScheduleDate(), slot.getTimeSlot())) {
            throw new IllegalArgumentException("排班不存在、已停诊或已超过预约时间");
        }
    }

    private void requireOpenSlot(BookingSlotVO slot) {
        if (slot == null || !Integer.valueOf(1).equals(slot.getStatus())
                || !Integer.valueOf(1).equals(slot.getDoctorStatus()) || !Integer.valueOf(1).equals(slot.getDepartmentStatus())
                || !"DOCTOR".equals(slot.getDoctorRoleType()) || !"CLINICAL".equals(slot.getDepartmentType())) {
            throw new IllegalArgumentException("排班不存在或已停诊");
        }
    }

    private BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_MONEY) > 0 || value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("金额不正确，最多保留两位小数");
        }
        return value.setScale(2);
    }

    private String method(PaymentRequest request) {
        if (request == null || !METHODS.contains(Objects.toString(request.payMethod(), ""))) throw new IllegalArgumentException("支付方式不正确");
        return request.payMethod();
    }
    private void requireLocalPaymentMethod(String method) {
        if (nativeWechatEnabled && "wechat".equals(method)) {
            throw new IllegalArgumentException("微信须从真实扫码入口付款；此入口仅支持余额和支付宝教学模拟支付");
        }
    }
    private void requireSameMethod(String requested, String stored) {
        if (!requested.equals(stored)) throw new IllegalArgumentException("订单已使用其他渠道付款，请刷新订单");
    }
    private void requireLog(int rows) { if (rows != 1) throw new IllegalStateException("支付流水保存失败，操作已撤销"); }
    private String text(String value, int max, String label, boolean required) {
        if (value == null) value = "";
        value = value.strip();
        if ((required && value.isEmpty()) || value.codePointCount(0, value.length()) > max) throw new IllegalArgumentException(label + "为空或长度超限");
        return value;
    }
    private ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "记录不存在或无权访问"); }
}
