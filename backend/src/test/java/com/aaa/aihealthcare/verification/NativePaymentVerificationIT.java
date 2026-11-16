package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.entity.Medicine;
import com.aaa.aihealthcare.payment.*;
import com.aaa.aihealthcare.service.MedicineService;
import com.aaa.aihealthcare.vo.PaymentRequest;
import com.aaa.aihealthcare.vo.RechargeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** MySQL、Mapper、事务和业务真实执行，微信通道仅使用离线替身。 */
@ContextConfiguration(classes={IsolatedMysqlConfiguration.class,NativePaymentVerificationIT.NativeTestConfiguration.class})
@TestPropertySource(properties="healthcare.payment.wechat-native.enabled=true")
class NativePaymentVerificationIT extends MysqlVerificationSupport {
    @Autowired NativePaymentService nativePayments;
    @Autowired FakeGateway gateway;
    @Autowired MedicineService medicines;

    @Configuration(proxyBeanMethods=false)
    @Import({NativePaymentSchemaInitializer.class,NativePaymentGuard.class,NativePaymentService.class})
    static class NativeTestConfiguration {
        @Bean NativeWechatProperties nativeProperties() {
            NativeWechatProperties p=new NativeWechatProperties(); p.setEnabled(true);
            p.setCashierSigningKey("synthetic-cashier-key-for-isolated-mysql-only-2026"); return p;
        }
        @Bean FakeGateway gateway() { return new FakeGateway(); }
    }
    static class FakeGateway implements NativeWechatGateway {
        final Map<String,Order> orders=new ConcurrentHashMap<>();
        final Map<String,String> states=new ConcurrentHashMap<>();
        final AtomicInteger creates=new AtomicInteger();
        volatile boolean creationTimeout;
        volatile boolean queryTimeout;
        volatile Trade notification;
        volatile String closeOverride;
        volatile CountDownLatch createEntered;
        volatile CountDownLatch resumeCreate;
        public Prepay create(Order order) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"微信HTTP不能持有数据库事务");
            creates.incrementAndGet(); orders.put(order.outTradeNo(),order); states.put(order.outTradeNo(),"NOTPAY");
            if(createEntered!=null) {
                createEntered.countDown();
                try { if(!resumeCreate.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("synthetic gate timeout"); }
                catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
            }
            if (creationTimeout) throw new GatewayException("TRANSPORT_ERROR","synthetic timeout",true);
            return new Prepay("weixin://wxpay/bizpayurl/up?synthetic="+order.outTradeNo(),"synthetic-prepay");
        }
        public Trade query(String no) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (queryTimeout) throw new GatewayException("TRANSPORT_ERROR","synthetic timeout",true);
            Order order=orders.get(no);
            String state=states.getOrDefault(no,"NOT_FOUND");
            return new Trade(no,"SUCCESS".equals(state)?"WX_"+no:null,order==null?null:order.totalFee(),state,Instant.now(),"synthetic-payer");
        }
        public Closed close(String no) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (closeOverride!=null) return new Closed(closeOverride);
            if ("SUCCESS".equals(states.get(no))) return new Closed("PAID");
            if (!orders.containsKey(no)) return new Closed("NOT_FOUND");
            states.put(no,"CLOSED"); return new Closed("CLOSED");
        }
        public Trade parseNotification(String xml) {
            if (!"synthetic-valid".equals(xml) || notification==null) throw new GatewayException("SIGN_INVALID","synthetic bad sign",true);
            return notification;
        }
        void clear() { orders.clear(); states.clear(); creates.set(0); creationTimeout=false; queryTimeout=false; notification=null; closeOverride=null; createEntered=null;resumeCreate=null; }
    }
    @BeforeEach void resetGateway() { gateway.clear(); }

    @Test void appointmentPrepayDoesNotSettleUntilVerifiedSuccess() {
        slot(100,1,1); order(200,10,100,"unpaid",false,0);
        var view=create("APPOINTMENT",200L,null,"operation");
        assertEquals("PENDING",view.status()); assertEquals("unpaid",status("appointment",200));
        assertEquals(0,count("SELECT COUNT(*) FROM balance_log"));
        gateway.states.put(no(view),"SUCCESS");
        view=nativePayments.ownerView(10L,view.paymentId(),true);
        assertEquals("SUCCEEDED",view.status()); assertEquals("pending",status("appointment",200));
        assertEquals(new BigDecimal("100.00"),balance(10));
        assertEquals(1,count("SELECT COUNT(*) FROM balance_log WHERE type='wechat_order_pay'"));
    }

    @Test void duplicateNotificationsAndQueriesCreditRechargeOnce() throws Exception {
        var view=create("RECHARGE",null,new BigDecimal("50.00"),"recharge-one");
        assertEquals(new BigDecimal("100.00"),balance(10));
        gateway.states.put(no(view),"SUCCESS"); gateway.notification=gateway.query(no(view));
        List<Outcome> results=race(THREADS,i->{nativePayments.acceptNotification("synthetic-valid");return true;});
        assertTrue(results.stream().allMatch(Outcome::success));
        assertEquals(new BigDecimal("150.00"),balance(10));
        assertEquals(1,count("SELECT COUNT(*) FROM balance_log WHERE type='recharge'"));
        assertEquals("SUCCEEDED",nativePayments.ownerView(10L,view.paymentId(),true).status());
        assertEquals("SUCCEEDED",create("RECHARGE",null,new BigDecimal("50.00"),"recharge-one").status());
        assertEquals(1,gateway.creates.get());
    }

    @Test void nativePrescriptionHoldsStockBeforeMoneyArrives() {
        slot(100,2,2); medicine(1); unpaidRx(300,200,10,1); unpaidRx(301,201,11,1);
        var first=create("PRESCRIPTION",300L,null,null);
        assertEquals("unpaid",status("prescription",300));
        assertThrows(IllegalArgumentException.class,()->nativePayments.create(11L,new NativePaymentService.CreateRequest("PRESCRIPTION",301L,null),null));
        assertEquals(1,gateway.creates.get());
        assertThrows(IllegalArgumentException.class,()->medicines.updateStatus(1L,0));
        Medicine change=medicines.getById(1L); change.setExpectedStock(1); change.setStock(0);
        assertThrows(IllegalArgumentException.class,()->medicines.update(change));
        gateway.states.put(no(first),"SUCCESS");
        assertEquals("SUCCEEDED",nativePayments.ownerView(10L,first.paymentId(),true).status());
        consultation.dispenseAsPharmacist(300L);
        assertEquals(0,count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals(new BigDecimal("100.00"),balance(10));
    }

    @Test void nativePrescriptionSuccessNotifiesPharmacistsOnceAcrossCallbacksAndQueries() throws Exception {
        slot(100,1,1); medicine(1); unpaidRx(300,200,10,1);
        addUser(4,"PHARMACIST",2L,"0.00");
        var view=create("PRESCRIPTION",300L,null,"pharmacist-message");
        assertEquals(0,count("SELECT COUNT(*) FROM user_notification"),"只生成二维码不能提前通知取药");
        gateway.states.put(no(view),"SUCCESS"); gateway.notification=gateway.query(no(view));
        var outcomes=race(THREADS,i->{nativePayments.acceptNotification("synthetic-valid");return true;});
        assertTrue(outcomes.stream().allMatch(Outcome::success));
        assertEquals("SUCCEEDED",nativePayments.ownerView(10L,view.paymentId(),true).status());
        for(long id:List.of(2L,3L,4L,10L)) {
            assertEquals(1,count("SELECT COUNT(*) FROM user_notification WHERE user_id=? AND type='prescription_paid'",id));
        }
        assertEquals(2,count("SELECT COUNT(*) FROM user_notification WHERE biz_type='prescription' AND biz_id=300"));
        consultation.dispenseAsPharmacist(300L);
        for(long id:List.of(2L,3L,4L,10L)) {
            assertEquals(1,count("SELECT COUNT(*) FROM user_notification WHERE user_id=? AND type='prescription_dispensed'",id));
        }
    }

    @Test void nativePharmacistNotificationFailureRollsBackLocalSettlementThenRetriesOnce() {
        slot(100,1,1); medicine(1); unpaidRx(300,200,10,1);
        var view=create("PRESCRIPTION",300L,null,"pharmacist-notification-fault");
        gateway.states.put(no(view),"SUCCESS"); gateway.notification=gateway.query(no(view));
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(nativePayments);
        var original=(com.aaa.aihealthcare.dao.NotificationDao)org.springframework.test.util.ReflectionTestUtils.getField(target,"notifications");
        org.springframework.test.util.ReflectionTestUtils.setField(target,"notifications",PharmacistNotificationFaults.armAtPharmacistInsert(original,jdbc));
        try {
            DataAccessException error=assertThrows(DataAccessException.class,()->nativePayments.acceptNotification("synthetic-valid"));
            Throwable cause=error;
            while(cause!=null && !(cause instanceof java.sql.SQLException)) cause=cause.getCause();
            assertInstanceOf(java.sql.SQLException.class,cause);
            assertEquals("45000",((java.sql.SQLException)cause).getSQLState());
            assertTrue(cause.getMessage().contains("verification notification failure"));
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(target,"notifications",original); jdbc.update("DELETE FROM _verification_fault"); }
        assertEquals("unpaid",status("prescription",300)); assertEquals("pending_rx_pay",status("appointment",200));
        assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM wechat_native_payment WHERE id=?",String.class,view.paymentId()));
        assertEquals(0,count("SELECT COUNT(*) FROM balance_log")); assertEquals(0,count("SELECT COUNT(*) FROM user_notification"));
        nativePayments.acceptNotification("synthetic-valid");
        nativePayments.acceptNotification("synthetic-valid");
        assertEquals("paid",status("prescription",300));
        assertEquals(1,count("SELECT COUNT(*) FROM user_notification WHERE user_id=3 AND type='prescription_paid'"));
        assertEquals(1,count("SELECT COUNT(*) FROM balance_log WHERE type='wechat_rx_pay'"));
    }

    @Test void closingUnpaidNativeOrderReleasesStockAndAllowsNewOrder() {
        slot(100,2,2); medicine(1); unpaidRx(300,200,10,1); unpaidRx(301,201,11,1);
        var first=create("PRESCRIPTION",300L,null,null);
        assertEquals("CLOSED",nativePayments.close(10L,first.paymentId()).status());
        assertEquals("PENDING",nativePayments.create(11L,new NativePaymentService.CreateRequest("PRESCRIPTION",301L,null),null).status());
        assertEquals(0,count("SELECT COUNT(*) FROM balance_log"));
    }

    @Test void unknownCreationNeverReleasesStockUntilChannelClosure() {
        slot(100,1,1); medicine(1); unpaidRx(300,200,10,1); gateway.creationTimeout=true;
        var view=create("PRESCRIPTION",300L,null,null); assertEquals("UNKNOWN",view.status());
        assertThrows(IllegalArgumentException.class,()->medicines.updateStatus(1L,0));
        gateway.queryTimeout=true;
        assertEquals("UNKNOWN",nativePayments.ownerView(10L,view.paymentId(),true).status());
        gateway.queryTimeout=false; gateway.closeOverride="NOT_FOUND";
        assertEquals("CLOSING",nativePayments.close(10L,view.paymentId()).status());
        assertThrows(IllegalArgumentException.class,()->medicines.updateStatus(1L,0));
        jdbc.update("UPDATE wechat_native_payment SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=?",view.paymentId());
        assertEquals("CLOSED",nativePayments.close(10L,view.paymentId()).status());
        assertTrue(medicines.updateStatus(1L,0));
    }

    @Test void cancelAndExpirationCannotReleaseActiveNativeAppointmentQuota() {
        slot(100,1,1); order(200,10,100,"unpaid",false,0);
        var view=create("APPOINTMENT",200L,null,null);
        assertThrows(IllegalArgumentException.class,()->patient.cancel(10L,200L));
        jdbc.update("UPDATE appointment SET create_time=DATE_SUB(NOW(),INTERVAL 16 MINUTE) WHERE id=200");
        assertFalse(appointments.expireUnpaid(200L));
        assertEquals(1,count("SELECT booked_count FROM schedule WHERE id=100"));
        assertEquals("CLOSED",nativePayments.close(10L,view.paymentId()).status());
        assertTrue(appointments.expireUnpaid(200L));
        assertEquals(0,count("SELECT booked_count FROM schedule WHERE id=100"));
    }

    @Test void paymentWonCloseRaceIsSettledRatherThanCalledClosed() {
        slot(100,1,1); order(200,10,100,"unpaid",false,0);
        var view=create("APPOINTMENT",200L,null,null); gateway.states.put(no(view),"SUCCESS");
        assertEquals("SUCCEEDED",nativePayments.close(10L,view.paymentId()).status());
        assertEquals("pending",status("appointment",200));
        assertThrows(IllegalArgumentException.class,()->patient.cancel(10L,200L));
        assertEquals(0,count("SELECT COUNT(*) FROM balance_log WHERE type='mock_order_refund'"));
    }

    @Test void wrongAmountOrFakeNotificationNeverSettles() {
        var view=create("RECHARGE",null,new BigDecimal("50.00"),"correct-amount");
        assertThrows(NativeWechatGateway.GatewayException.class,()->nativePayments.acceptNotification("forged"));
        gateway.notification=new NativeWechatGateway.Trade(no(view),"wx-wrong-amount",1L,"SUCCESS",Instant.now(),"any-payer");
        assertThrows(IllegalArgumentException.class,()->nativePayments.acceptNotification("synthetic-valid"));
        assertEquals(new BigDecimal("100.00"),balance(10));
        assertEquals(0,count("SELECT COUNT(*) FROM balance_log"));
    }

    @Test void paymentLedgerFailureRollsBackThenTrustedQueryRecovers() {
        var view=create("RECHARGE",null,new BigDecimal("50.00"),"ledger-recovery");
        gateway.states.put(no(view),"SUCCESS"); gateway.notification=gateway.query(no(view));
        jdbc.update("INSERT INTO _verification_fault VALUES ('balance_log')");
        try { assertThrows(DataAccessException.class,()->nativePayments.acceptNotification("synthetic-valid")); }
        finally { jdbc.update("DELETE FROM _verification_fault"); }
        assertEquals(new BigDecimal("100.00"),balance(10));
        assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM wechat_native_payment WHERE id=?",String.class,view.paymentId()));
        assertEquals("SUCCEEDED",nativePayments.ownerView(10L,view.paymentId(),true).status());
        assertEquals(new BigDecimal("150.00"),balance(10));
    }

    @Test void ownersAndCashierCapabilitiesAreBounded() {
        var view=create("RECHARGE",null,new BigDecimal("50.00"),"owner-boundary");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->nativePayments.ownerView(11L,view.paymentId(),false));
        String token=view.cashierUrl().substring(view.cashierUrl().lastIndexOf('/')+1);
        assertEquals(view.paymentId(),nativePayments.cashierView(token).paymentId());
        assertNull(nativePayments.cashierView(token).balance());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->nativePayments.cashierView(token+"x"));
        assertTrue(nativePayments.cashierCode(token).startsWith("weixin://"));
        nativePayments.close(10L,view.paymentId());
        assertThrows(IllegalArgumentException.class,()->nativePayments.cashierCode(token));
    }

    @Test void rechargeKeyCannotChangeAmountAndLegacyMockRoutesAreBlocked() {
        create("RECHARGE",null,new BigDecimal("50.00"),"stable-key");
        assertThrows(IllegalArgumentException.class,()->create("RECHARGE",null,new BigDecimal("60.00"),"stable-key"));
        assertThrows(IllegalArgumentException.class,()->patient.recharge(10L,new RechargeRequest(new BigDecimal("50.00"),"wechat"),"bypass"));
        slot(100,1,1); order(200,10,100,"unpaid",false,0);
        assertThrows(IllegalArgumentException.class,()->patient.payAppointment(10L,200L,new PaymentRequest("wechat")));
        patient.payAppointment(10L,200L,new PaymentRequest("alipay"));
        assertEquals(1,count("SELECT COUNT(*) FROM balance_log WHERE type='mock_order_pay' AND pay_method='alipay'"));
        assertEquals(new BigDecimal("100.00"),balance(10));
    }

    @Test void alipaySimulatedCancellationRecordsOriginalChannelRefundOnce() throws Exception {
        slot(100,1,1); order(200,10,100,"unpaid",false,0);
        patient.payAppointment(10L,200L,new PaymentRequest("alipay"));
        assertEquals("pending",status("appointment",200));
        assertEquals(new BigDecimal("100.00"),balance(10));
        List<Outcome> cancelled=race(THREADS,i->patient.cancel(10L,200L));
        assertTrue(cancelled.stream().allMatch(Outcome::success));
        assertEquals("cancelled",status("appointment",200));
        assertEquals(0,count("SELECT booked_count FROM schedule WHERE id=100"));
        assertEquals(new BigDecimal("100.00"),balance(10),"模拟支付宝退款不能额外增加应用余额");
        assertEquals(1,count("SELECT COUNT(*) FROM balance_log WHERE type='mock_order_refund' AND pay_method='alipay' AND order_id=200 AND amount=20"));
        assertEquals(0,gateway.creates.get(),"支付宝模拟支付和退款不能调用真实微信通道");
        printEvidence("alipay-original-channel-refund","concurrentCancels=12,refunds=1,balanceUnchanged=true,nativeCreates=0");
    }

    @Test void alipayRechargeRemainsIdempotentAlongsideRealWechat() throws Exception {
        List<Outcome> results=race(THREADS,i->patient.recharge(10L,new RechargeRequest(new BigDecimal("50.00"),"alipay"),"synthetic-alipay-recharge"));
        assertTrue(results.stream().allMatch(Outcome::success));
        assertEquals(new BigDecimal("150.00"),balance(10));
        assertEquals(1,count("SELECT COUNT(*) FROM balance_log WHERE type='recharge' AND pay_method='alipay'"));
        assertEquals(0,count("SELECT COUNT(*) FROM wechat_native_payment"));
        assertEquals(0,gateway.creates.get());
    }

    @Test void simulatedAlipayCannotPayWhileNativeOrderIsUncertain() {
        slot(100,1,1); order(200,10,100,"unpaid",false,0);
        var payment=create("APPOINTMENT",200L,null,null);
        assertThrows(IllegalArgumentException.class,()->patient.payAppointment(10L,200L,new PaymentRequest("alipay")));
        nativePayments.close(10L,payment.paymentId());
        patient.payAppointment(10L,200L,new PaymentRequest("alipay"));
        assertEquals("pending",status("appointment",200));
        assertEquals(new BigDecimal("100.00"),balance(10));
    }

    @Test void alipayPrescriptionUsesExistingReservationAndNeverCallsWechat() {
        slot(100,1,1); medicine(2); unpaidRx(300,200,10,1);
        patient.payPrescription(10L,300L,new PaymentRequest("alipay"));
        assertEquals("paid",status("prescription",300));
        assertEquals(1,count("SELECT COUNT(*) FROM balance_log WHERE type='mock_rx_pay' AND pay_method='alipay'"));
        assertThrows(IllegalArgumentException.class,()->medicines.updateStatus(1L,0));
        consultation.dispenseAsPharmacist(300L);
        assertEquals(1,count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals(new BigDecimal("100.00"),balance(10));
        assertEquals(0,gateway.creates.get());
    }

    @Test void alipayWithoutTrustedMockReceiptCannotClaimSimulatedRefund() {
        slot(100,1,1); order(200,10,100,"pending",true,0);
        jdbc.update("UPDATE appointment SET pay_method='alipay' WHERE id=200");
        assertThrows(IllegalArgumentException.class,()->patient.cancel(10L,200L));
        assertEquals("pending",status("appointment",200));
        assertEquals(1,count("SELECT booked_count FROM schedule WHERE id=100"));
        assertEquals(0,count("SELECT COUNT(*) FROM balance_log"));
    }

    @Test void simulatedRefundSqlFailureRollsBackCancellationAndQuota() {
        slot(100,1,1); order(200,10,100,"unpaid",false,0);
        patient.payAppointment(10L,200L,new PaymentRequest("alipay"));
        jdbc.update("INSERT INTO _verification_fault VALUES ('balance_log')");
        try {
            DataAccessException failure=assertThrows(DataAccessException.class,()->patient.cancel(10L,200L));
            Throwable cause=failure;
            while(cause!=null && !(cause instanceof java.sql.SQLException)) cause=cause.getCause();
            assertInstanceOf(java.sql.SQLException.class,cause);
            assertEquals("45000",((java.sql.SQLException)cause).getSQLState());
            assertTrue(cause.getMessage().contains("verification balance-log failure"));
        } finally {jdbc.update("DELETE FROM _verification_fault");}
        assertEquals("pending",status("appointment",200));
        assertEquals(1,count("SELECT booked_count FROM schedule WHERE id=100"));
        assertEquals(0,count("SELECT COUNT(*) FROM balance_log WHERE type='mock_order_refund'"));
        patient.cancel(10L,200L);
        assertEquals(1,count("SELECT COUNT(*) FROM balance_log WHERE type='mock_order_refund'"));
    }

    @Test void activeNativePaymentBlocksBalanceDoublePayment() {
        slot(100,1,1); order(200,10,100,"unpaid",false,0);
        var view=create("APPOINTMENT",200L,null,null);
        assertThrows(IllegalArgumentException.class,()->patient.payAppointment(10L,200L,new PaymentRequest("balance")));
        nativePayments.close(10L,view.paymentId());
        patient.payAppointment(10L,200L,new PaymentRequest("balance"));
        assertEquals(new BigDecimal("80.00"),balance(10));
    }

    @Test void latePaidInvalidBusinessIsRecordedForManualReview() {
        slot(100,1,1); order(200,10,100,"unpaid",false,0);
        var view=create("APPOINTMENT",200L,null,null);
        jdbc.update("UPDATE appointment SET status='cancelled' WHERE id=200");
        gateway.states.put(no(view),"SUCCESS");
        assertEquals("NEEDS_REVIEW",nativePayments.ownerView(10L,view.paymentId(),true).status());
        assertEquals("cancelled",status("appointment",200));
        assertEquals(new BigDecimal("100.00"),balance(10));
        assertEquals("NEEDS_REVIEW",create("APPOINTMENT",200L,null,null).status());
    }

    @Test void concurrentNativePrepaymentsCannotOversubscribeLastMedicine() throws Exception {
        slot(100,THREADS,THREADS); medicine(1);
        for (int i=0;i<THREADS;i++) unpaidRx(300+i,200+i,10+i,1);
        ExecutorService workers=Executors.newFixedThreadPool(THREADS);
        CyclicBarrier start=new CyclicBarrier(THREADS);
        try {
            List<Future<Boolean>> futures=new java.util.ArrayList<>();
            for(int i=0;i<THREADS;i++) {
                final int index=i;
                futures.add(workers.submit(()->{
                    start.await(10,TimeUnit.SECONDS);
                    try {
                        nativePayments.create(10L+index,new NativePaymentService.CreateRequest("PRESCRIPTION",300L+index,null),null);
                        return true;
                    } catch (IllegalArgumentException rejection) {
                        assertTrue(rejection.getMessage().contains("库存不足")); return false;
                    }
                }));
            }
            int accepted=0;
            for(Future<Boolean> future:futures) if(future.get(30,TimeUnit.SECONDS)) accepted++;
            assertEquals(1,accepted); assertEquals(1,gateway.creates.get());
            assertEquals(1,count("SELECT COUNT(*) FROM wechat_native_payment WHERE state='PENDING'"));
            assertEquals(0,count("SELECT COUNT(*) FROM balance_log"));
            printEvidence("native-prepay-stock-race","threads=12,accepted=1,rejected=11,charges=0");
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS)); }
    }

    @Test void lateCreateTimeoutCannotEraseConcurrentCloseIntent() throws Exception {
        gateway.createEntered=new CountDownLatch(1); gateway.resumeCreate=new CountDownLatch(1);
        gateway.creationTimeout=true; gateway.closeOverride="NOT_FOUND";
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<NativePaymentService.View> creating=worker.submit(()->create("RECHARGE",null,new BigDecimal("1.00"),"close-create-race"));
            assertTrue(gateway.createEntered.await(10,TimeUnit.SECONDS));
            long id=count("SELECT id FROM wechat_native_payment");
            assertEquals("CLOSING",nativePayments.close(10L,id).status());
            gateway.resumeCreate.countDown();
            assertEquals("CLOSING",creating.get(15,TimeUnit.SECONDS).status());
            assertEquals("CLOSING",jdbc.queryForObject("SELECT state FROM wechat_native_payment WHERE id=?",String.class,id));
            assertEquals(new BigDecimal("100.00"),balance(10));
        } finally { gateway.resumeCreate.countDown(); worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS)); }
    }

    @Test void paidPrescriptionReviewKeepsItsReservation() {
        slot(100,1,1); medicine(1); unpaidRx(300,200,10,1);
        var view=create("PRESCRIPTION",300L,null,null);
        jdbc.update("UPDATE appointment SET status='cancelled' WHERE id=200");
        gateway.states.put(no(view),"SUCCESS");
        assertEquals("NEEDS_REVIEW",nativePayments.ownerView(10L,view.paymentId(),true).status());
        assertThrows(IllegalArgumentException.class,()->medicines.updateStatus(1L,0));
        assertEquals(1,count("SELECT stock FROM medicine WHERE id=1"));
    }

    private NativePaymentService.View create(String type,Long id,BigDecimal amount,String key) {
        return nativePayments.create(10L,new NativePaymentService.CreateRequest(type,id,amount),key);
    }
    private String no(NativePaymentService.View view) { return jdbc.queryForObject("SELECT merchant_order_no FROM wechat_native_payment WHERE id=?",String.class,view.paymentId()); }
    private void unpaidRx(long id,long orderId,long userId,int quantity) {
        order(orderId,userId,100,"pending_rx_pay",true,0); paidPrescription(id,orderId,userId,quantity);
        jdbc.update("UPDATE prescription SET status='unpaid',pay_time=NULL,pay_method=NULL WHERE id=?",id);
    }
}
