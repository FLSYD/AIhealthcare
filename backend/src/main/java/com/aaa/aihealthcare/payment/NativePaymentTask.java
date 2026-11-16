package com.aaa.aihealthcare.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 本地学习环境以主动查单补偿不具备公网回调的情况。 */
@Component
@ConditionalOnProperty(name="healthcare.payment.wechat-native.enabled", havingValue="true")
public class NativePaymentTask {
    private final NativePaymentService service;
    public NativePaymentTask(NativePaymentService service) { this.service=service; }
    @Scheduled(fixedDelay=15000, initialDelay=20000)
    public void synchronize() { service.synchronizePending(); }
}
