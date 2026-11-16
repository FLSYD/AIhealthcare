package com.aaa.aihealthcare.payment;

import com.aaa.aihealthcare.dao.NativePaymentDao;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="healthcare.payment.wechat-native.enabled", havingValue="true")
public class NativePaymentGuard {
    private final NativePaymentDao payments;
    public NativePaymentGuard(NativePaymentDao payments, NativePaymentSchemaInitializer schema) { this.payments = payments; }
    public long reservedQuantity(Long medicineId) {
        return payments.selectReservedQuantities(medicineId).stream().mapToLong(Integer::longValue).sum();
    }
    public boolean hasActiveAppointment(Long appointmentId) {
        return payments.countActiveAppointment(appointmentId) > 0;
    }
    public void requireNoActive(String type, Long id) {
        if (payments.lockByActiveKey(type + ":" + id) != null) {
            throw new IllegalArgumentException("微信扫码支付结果待确认，请先查看或关闭支付单");
        }
    }
}
