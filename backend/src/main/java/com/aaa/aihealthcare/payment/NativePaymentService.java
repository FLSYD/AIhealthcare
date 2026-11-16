package com.aaa.aihealthcare.payment;

import com.aaa.aihealthcare.common.AppointmentPaymentWindow;
import com.aaa.aihealthcare.common.AppointmentStatus;
import com.aaa.aihealthcare.dao.*;
import com.aaa.aihealthcare.entity.*;
import com.aaa.aihealthcare.vo.BookingSlotVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 微信 HTTP 调用在本地短事务之外；业务成功只能由已验签的查单或通知驱动。 */
@Service
@ConditionalOnProperty(name="healthcare.payment.wechat-native.enabled", havingValue="true")
public class NativePaymentService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Set<String> ACTIVE = Set.of("CREATING", "PENDING", "UNKNOWN", "CLOSING");
    private final NativePaymentDao payments;
    private final PatientCommandDao patients;
    private final DoctorConsultationDao consultation;
    private final MedicineDao medicines;
    private final SysUserDao users;
    private final NotificationDao notifications;
    private final NativePaymentGuard guard;
    private final NativeWechatGateway gateway;
    private final NativeWechatProperties properties;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;

    public record CreateRequest(String businessType, Long businessId, BigDecimal amount) {}
    public record View(Long paymentId, String businessType, String status, String amount, long amountFen,
                       long expiresAt, String codeImageUrl, String cashierUrl, String message, String balance) {}
    private record Prepared(NativePayment payment, boolean created) {}

    public NativePaymentService(NativePaymentDao payments, PatientCommandDao patients,
            DoctorConsultationDao consultation, MedicineDao medicines, SysUserDao users,
            NotificationDao notifications, NativePaymentGuard guard, NativeWechatGateway gateway,
            NativeWechatProperties properties, PlatformTransactionManager manager, JdbcTemplate jdbc) {
        this.payments=payments; this.patients=patients; this.consultation=consultation;
        this.medicines=medicines; this.users=users; this.notifications=notifications; this.guard=guard;
        this.gateway=gateway; this.properties=properties; this.transaction=new TransactionTemplate(manager); this.jdbc=jdbc;
        if (properties.getCashierSigningKey()==null || properties.getCashierSigningKey().length()<32) {
            throw new IllegalStateException("请配置独立的扫码支付页签名密钥，至少32字符");
        }
    }

    public View create(Long userId, CreateRequest request, String key) {
        outsideTransaction();
        Prepared prepared=transaction.execute(status -> prepare(userId, request, key));
        NativePayment payment=Objects.requireNonNull(prepared).payment();
        if (prepared.created()) {
            try {
                NativeWechatGateway.Prepay result=gateway.create(new NativeWechatGateway.Order(
                        payment.getMerchantOrderNo(), payment.getAmountFen(), description(payment),
                        payment.getExpiresAt().atZone(ZONE).toInstant()));
                transaction.executeWithoutResult(status -> {
                    users.lockActiveAdminIds();
                    NativePayment current=payments.lock(payment.getId());
                    if (ACTIVE.contains(current.getState())) {
                        current.setCodeUrl(result.codeUrl()); current.setPrepayId(result.prepayId());
                        if (!"CLOSING".equals(current.getState())) current.setState("PENDING");
                        current.setErrorCode(null); current.setErrorMessage(null); payments.update(current);
                    }
                });
            } catch (NativeWechatGateway.GatewayException error) {
                transaction.executeWithoutResult(status -> {
                    users.lockActiveAdminIds(); NativePayment current=payments.lock(payment.getId());
                    if (!ACTIVE.contains(current.getState())) return;
                    if (!(error.isUncertain() && "CLOSING".equals(current.getState()))) {
                        current.setState(error.isUncertain() ? "UNKNOWN" : "FAILED");
                    }
                    if (!error.isUncertain()) current.setActiveKey(null);
                    current.setErrorCode(error.getCode());
                    current.setErrorMessage(error.isUncertain() ? "微信支付结果待确认，请稍后查询" : "微信暂不接受该笔支付，请重试或选择余额支付");
                    payments.update(current);
                });
            }
        }
        return ownerView(userId, payment.getId(), !prepared.created());
    }

    private Prepared prepare(Long userId, CreateRequest request, String key) {
        if (request==null || !Set.of("APPOINTMENT","PRESCRIPTION","RECHARGE").contains(Objects.toString(request.businessType(),""))) {
            throw new IllegalArgumentException("支付业务类型不正确");
        }
        users.lockActiveAdminIds(); requireBalance(userId);
        String type=request.businessType();
        if ("RECHARGE".equals(type)) {
            if (key==null || key.isBlank() || key.length()>200) throw new IllegalArgumentException("充值请求标识不正确");
            long requested=fen(request.amount());
            if (requested>1_000_000) throw new IllegalArgumentException("单次充值不能超过10000元");
            NativePayment previous=payments.lockByRequest(userId, hash(key));
            if (previous!=null) {
                if (previous.getAmountFen()!=requested) throw new IllegalArgumentException("同一充值标识不能用于不同金额");
                return new Prepared(previous,false);
            }
        } else if (request.businessId()==null || request.businessId()<1) {
            throw new IllegalArgumentException("请选择有效订单");
        }
        NativePayment previous="RECHARGE".equals(type) ? null : payments.lockByActiveKey(type+":"+request.businessId());
        if (previous!=null) {
            if (!userId.equals(previous.getUserId())) throw forbidden();
            return new Prepared(previous,false);
        }
        long total;
        LocalDateTime expires=now().plusMinutes(5);
        if ("APPOINTMENT".equals(type)) {
            Appointment order=patients.lockAppointment(request.businessId(),userId);
            if (order==null) throw forbidden();
            if (!AppointmentStatus.UNPAID.equals(order.getStatus()) || order.getPayTime()!=null) {
                throw new IllegalArgumentException("该预约已支付或当前状态不能扫码付款");
            }
            if (order.getCreateTime()==null || AppointmentPaymentWindow.isExpired(order.getCreateTime(),now())) {
                throw new IllegalArgumentException("订单已超过付款期限，请重新预约");
            }
            LocalDateTime appointmentExpiry=order.getCreateTime().plusMinutes(15);
            if (expires.isAfter(appointmentExpiry)) expires=appointmentExpiry;
            if (expires.isBefore(now().plusMinutes(1))) throw new IllegalArgumentException("订单即将过期，请重新预约后付款");
            BookingSlotVO slot=patients.lockSlot(order.getScheduleId());
            if (slot==null || !Integer.valueOf(1).equals(slot.getStatus()) || !Integer.valueOf(1).equals(slot.getDoctorStatus())
                    || !Integer.valueOf(1).equals(slot.getDepartmentStatus()) || !"DOCTOR".equals(slot.getDoctorRoleType())
                    || !"CLINICAL".equals(slot.getDepartmentType()) || slot.getScheduleDate()==null || slot.getScheduleDate().isBefore(LocalDate.now(ZONE))) {
                throw new IllegalArgumentException("排班已停用或超过就诊日期，不能付款");
            }
            total=fen(order.getAmount());
        } else if ("PRESCRIPTION".equals(type)) {
            Prescription hint=patients.findPrescription(request.businessId(),userId);
            if (hint==null) throw forbidden();
            Appointment order=patients.lockAppointment(hint.getAppointmentId(),userId);
            Prescription rx=patients.lockPrescription(request.businessId(),userId,hint.getAppointmentId());
            if (order==null || rx==null) throw forbidden();
            if (!"unpaid".equals(rx.getStatus()) || !"pending_rx_pay".equals(order.getStatus())) {
                throw new IllegalArgumentException("当前处方状态不能扫码缴药费");
            }
            requireStock(rx.getId()); total=fen(rx.getTotalAmount());
        } else total=fen(request.amount());
        NativePayment value=new NativePayment(); value.setBusinessType(type); value.setBusinessId(request.businessId());
        value.setUserId(userId); value.setAmountFen(total); value.setState("CREATING"); value.setExpiresAt(expires);
        value.setMerchantOrderNo("AHN"+UUID.randomUUID().toString().replace("-","").substring(0,28));
        if ("RECHARGE".equals(type)) value.setRequestHash(hash(key));
        value.setActiveKey("RECHARGE".equals(type) ? "R:"+userId+":"+hash(key) : type+":"+request.businessId());
        if (payments.insert(value)!=1 || value.getId()==null) throw new IllegalStateException("支付单保存失败");
        return new Prepared(value,true);
    }

    private void requireStock(Long prescriptionId) {
        List<PrescriptionItem> items=consultation.selectItemsForUpdate(prescriptionId);
        if (items.isEmpty()) throw new IllegalArgumentException("处方没有药品明细");
        Map<Long,Integer> quantities=new TreeMap<>();
        for (PrescriptionItem item:items) {
            if (item.getMedicineId()==null || item.getQuantity()==null || item.getQuantity()<1 || item.getQuantity()>1000
                    || quantities.put(item.getMedicineId(),item.getQuantity())!=null) throw new IllegalArgumentException("处方明细异常");
        }
        List<Medicine> locked=consultation.selectMedicinesForUpdate(new ArrayList<>(quantities.keySet()));
        if (locked.size()!=quantities.size()) throw new IllegalArgumentException("处方药品不存在");
        for (Medicine medicine:locked) {
            long reserved=medicines.selectPaidQuantitiesForUpdate(medicine.getId()).stream().mapToLong(Integer::longValue).sum()
                    + guard.reservedQuantity(medicine.getId());
            if (!Integer.valueOf(1).equals(medicine.getStatus()) || medicine.getStock()==null
                    || medicine.getStock()-reserved<quantities.get(medicine.getId())) throw new IllegalArgumentException("药品库存不足或已停用，尚未收款");
        }
    }

    public View ownerView(Long userId, Long id, boolean refresh) {
        NativePayment payment=owned(userId,id);
        if (refresh) refresh(id,false);
        return view(owned(userId,id),true);
    }

    public View cashierView(String token) {
        Long id=verifyToken(token); refresh(id,false); return view(required(id),false);
    }

    public String cashierCode(String token) {
        NativePayment payment=required(verifyToken(token));
        if (!ACTIVE.contains(payment.getState()) || !now().isBefore(payment.getExpiresAt()) || payment.getCodeUrl()==null) {
            throw new IllegalArgumentException("该二维码已失效，请查看支付结果");
        }
        return payment.getCodeUrl();
    }

    public View close(Long userId, Long id) {
        outsideTransaction(); owned(userId,id);
        transaction.executeWithoutResult(status -> {
            users.lockActiveAdminIds(); NativePayment payment=payments.lock(id);
            if (ACTIVE.contains(payment.getState())) { payment.setState("CLOSING"); payments.update(payment); }
        });
        refresh(id,true); return view(owned(userId,id),true);
    }

    public void refresh(Long id, boolean forceClose) {
        outsideTransaction(); NativePayment payment=required(id);
        if (!ACTIVE.contains(payment.getState())) return;
        if (!forceClose && payments.claimQuery(id)!=1) return;
        try {
            NativeWechatGateway.Trade trade=gateway.query(payment.getMerchantOrderNo());
            if ("SUCCESS".equals(trade.tradeState())) { applySuccess(trade); return; }
            if ("CLOSED".equals(trade.tradeState())) { markClosed(id); return; }
            boolean closing=forceClose || "CLOSING".equals(payment.getState()) || !now().isBefore(payment.getExpiresAt());
            if (closing) {
                NativeWechatGateway.Closed closed=gateway.close(payment.getMerchantOrderNo());
                if ("CLOSED".equals(closed.state())) { markClosed(id); return; }
                if ("PAID".equals(closed.state())) {
                    NativeWechatGateway.Trade paid=gateway.query(payment.getMerchantOrderNo());
                    if ("SUCCESS".equals(paid.tradeState())) applySuccess(paid);
                    return;
                }
                // 创建请求超时后，早期的“不存在”不能证明迟到的请求不会建单；等待绝对付款期限结束。
                if ("NOT_FOUND".equals(closed.state()) && now().isAfter(payment.getExpiresAt().plusSeconds(20))) { markClosed(id); return; }
            }
        } catch (NativeWechatGateway.GatewayException failure) {
            transaction.executeWithoutResult(status -> {
                users.lockActiveAdminIds(); NativePayment current=payments.lock(id);
                if (!ACTIVE.contains(current.getState())) return;
                if (!"CLOSING".equals(current.getState())) current.setState("UNKNOWN");
                current.setErrorCode(failure.getCode()); current.setErrorMessage("微信支付结果暂未确认，请勿重复付款"); payments.update(current);
            });
        }
    }

    public void acceptNotification(String xml) { applySuccess(gateway.parseNotification(xml)); }

    private void applySuccess(NativeWechatGateway.Trade trade) {
        if (!"SUCCESS".equals(trade.tradeState()) || trade.transactionId()==null || trade.totalFee()==null) {
            throw new IllegalArgumentException("通知不包含可信支付成功结果");
        }
        try {
            transaction.executeWithoutResult(status -> {
                users.lockActiveAdminIds();
                NativePayment payment=payments.lockByMerchantOrderNo(trade.outTradeNo());
                if (payment==null || !Objects.equals(payment.getAmountFen(),trade.totalFee())) throw new IllegalArgumentException("微信订单金额或归属不匹配");
                if ("SUCCEEDED".equals(payment.getState()) || "NEEDS_REVIEW".equals(payment.getState())) {
                    if (!trade.transactionId().equals(payment.getTransactionId())) throw new IllegalArgumentException("微信交易号不匹配");
                    return;
                }
                if (patients.lockBalance(payment.getUserId())==null) throw new BusinessConflict();
                settleBusiness(payment);
                payment.setTransactionId(trade.transactionId()); payment.setState("SUCCEEDED"); payment.setActiveKey(null);
                payment.setErrorCode(null); payment.setErrorMessage(null); payments.update(payment);
            });
        } catch (BusinessConflict conflict) {
            transaction.executeWithoutResult(status -> {
                users.lockActiveAdminIds(); NativePayment payment=payments.lockByMerchantOrderNo(trade.outTradeNo());
                if (payment!=null && !"SUCCEEDED".equals(payment.getState())) {
                    payment.setTransactionId(trade.transactionId()); payment.setState("NEEDS_REVIEW");
                    payment.setErrorCode("BUSINESS_CONFLICT"); payment.setErrorMessage("已支付待人工处理，请勿重复支付"); payments.update(payment);
                }
            });
        }
    }

    private void settleBusiness(NativePayment payment) {
        Long userId=payment.getUserId(); BigDecimal amount=money(payment.getAmountFen());
        if ("RECHARGE".equals(payment.getBusinessType())) {
            BigDecimal balance=requireBalance(userId);
            if (patients.changeBalance(userId,amount)!=1) throw new BusinessConflict();
            log(userId,"recharge",amount,balance.add(amount),null,"微信扫码真实充值:"+payment.getMerchantOrderNo());
            return;
        }
        if ("APPOINTMENT".equals(payment.getBusinessType())) {
            Appointment appointment=patients.lockAppointment(payment.getBusinessId(),userId);
            if (appointment==null || !"unpaid".equals(appointment.getStatus()) || fen(appointment.getAmount())!=payment.getAmountFen()) throw new BusinessConflict();
            if (patients.payAppointment(appointment.getId(),userId,"wechat")!=1) throw new BusinessConflict();
            log(userId,"wechat_order_pay",amount,requireBalance(userId),appointment.getId(),"微信扫码真实挂号付款");
            notifications.insertForUser(userId,"appointment_paid","挂号付款成功","微信付款已确认，请按预约时间到院。","appointment",appointment.getId());
            notifications.insertForAppointmentDoctor(appointment.getId(),"appointment_paid","新增待诊预约","您有一条已付款预约。确认微信实付。");
        } else {
            Prescription hint=patients.findPrescription(payment.getBusinessId(),userId);
            if (hint==null) throw new BusinessConflict();
            Appointment appointment=patients.lockAppointment(hint.getAppointmentId(),userId);
            Prescription rx=patients.lockPrescription(hint.getId(),userId,hint.getAppointmentId());
            if (appointment==null || rx==null || !"unpaid".equals(rx.getStatus()) || !"pending_rx_pay".equals(appointment.getStatus())
                    || fen(rx.getTotalAmount())!=payment.getAmountFen()) throw new BusinessConflict();
            if (patients.payPrescription(rx.getId(),userId,"wechat")!=1 || patients.advanceToPickup(appointment.getId(),userId)!=1) throw new BusinessConflict();
            log(userId,"wechat_rx_pay",amount,requireBalance(userId),rx.getId(),"微信扫码真实药费付款");
            notifications.insertForUser(userId,"prescription_paid","药费付款成功","微信付款已确认，请等待取药。","appointment",appointment.getId());
            notifications.insertForAppointmentDoctor(appointment.getId(),"prescription_paid","处方已付款","患者已缴药费，处方已进入配药队列。");
            notifications.insertForPharmacists(rx.getId(),"prescription_paid","新增待发药处方","患者已缴纳药费，请到配药工作台核对处方并发药。");
        }
    }

    private void log(Long userId,String type,BigDecimal amount,BigDecimal balance,Long orderId,String remark) {
        if (patients.insertLog(userId,type,amount,balance,orderId,"wechat",remark)!=1) throw new IllegalStateException("微信支付账本写入失败，请稍后查单确认");
    }
    public void synchronizePending() { for (Long id:payments.selectPendingIds()) { try { refresh(id,false); } catch (RuntimeException ignored) { /* 保留待确认状态，下轮重试。 */ } } }
    private void markClosed(Long id) {
        transaction.executeWithoutResult(status -> {
            users.lockActiveAdminIds(); NativePayment payment=payments.lock(id);
            if (ACTIVE.contains(payment.getState())) { payment.setState("CLOSED"); payment.setActiveKey(null); payment.setCodeUrl(null); payment.setErrorCode(null); payment.setErrorMessage(null); payments.update(payment); }
        });
    }
    private View view(NativePayment payment,boolean owner) {
        String token=token(payment); String prefix="/api/payments/wechat/native/cashier/"+token;
        String message=switch(payment.getState()) {
            case "SUCCEEDED" -> "微信付款已确认";
            case "CLOSED" -> "该笔扫码支付已关闭，未收款";
            case "FAILED" -> "微信未受理该笔支付，请重新发起";
            case "NEEDS_REVIEW" -> "已支付待人工处理，请勿重复支付";
            case "CLOSING" -> "正在确认关闭结果，请勿重复支付";
            case "UNKNOWN" -> "支付结果待确认，请勿重复付款";
            default -> "微信真实支付，请使用另一台设备扫码";
        };
        String balance=owner && "RECHARGE".equals(payment.getBusinessType()) && "SUCCEEDED".equals(payment.getState())
                ? jdbc.queryForObject("SELECT balance FROM sys_user WHERE id=?",BigDecimal.class,payment.getUserId()).toPlainString() : null;
        return new View(payment.getId(),payment.getBusinessType(),payment.getState(),money(payment.getAmountFen()).toPlainString(),payment.getAmountFen(),
                payment.getExpiresAt().atZone(ZONE).toInstant().toEpochMilli(),
                payment.getCodeUrl()==null ? null : prefix+"/qr", properties.getCashierBaseUrl().replaceAll("/+$","")+"/cashier/"+token,message,balance);
    }
    private NativePayment owned(Long userId,Long id) { NativePayment p=required(id); if (!Objects.equals(userId,p.getUserId())) throw forbidden(); return p; }
    private NativePayment required(Long id) { NativePayment p=id==null ? null : payments.find(id); if (p==null) throw forbidden(); return p; }
    private BigDecimal requireBalance(Long userId) { BigDecimal b=patients.lockBalance(userId); if (b==null) throw forbidden(); return b; }
    private String token(NativePayment p) {
        long until=p.getExpiresAt().plusHours(1).atZone(ZONE).toEpochSecond();
        String payload=p.getId()+"."+until; return payload+"."+hmac(payload);
    }
    private Long verifyToken(String token) {
        if (token==null || token.length()>150) throw forbidden(); String[] parts=token.split("\\.");
        if (parts.length!=3) throw forbidden();
        try {
            String payload=parts[0]+"."+parts[1];
            if (Long.parseLong(parts[1])<Instant.now().getEpochSecond() || !MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.US_ASCII),hmac(payload).getBytes(StandardCharsets.US_ASCII))) throw forbidden();
            return Long.parseLong(parts[0]);
        } catch (NumberFormatException error) { throw forbidden(); }
    }
    private String hmac(String value) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(properties.getCashierSigningKey().getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("支付页签名初始化失败"); }
    }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static long fen(BigDecimal value) {
        if (value==null || value.signum()<=0 || value.stripTrailingZeros().scale()>2) throw new IllegalArgumentException("付款金额不正确");
        try { long result=value.movePointRight(2).longValueExact(); if (result>Integer.MAX_VALUE) throw new IllegalArgumentException("金额超过微信单笔允许范围"); return result; } catch (ArithmeticException error) { throw new IllegalArgumentException("付款金额不正确"); }
    }
    private static BigDecimal money(long fen) { return BigDecimal.valueOf(fen,2).setScale(2,RoundingMode.UNNECESSARY); }
    private static LocalDateTime now() { return LocalDateTime.now(ZONE); }
    private static String description(NativePayment payment) { return switch(payment.getBusinessType()) { case "APPOINTMENT" -> "学习项目挂号费"; case "PRESCRIPTION" -> "学习项目药费"; default -> "学习项目账户充值"; }; }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"支付单不存在或无权访问"); }
    private static void outsideTransaction() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("微信网络调用不能在数据库事务内执行"); }
    private static final class BusinessConflict extends RuntimeException {}
}
