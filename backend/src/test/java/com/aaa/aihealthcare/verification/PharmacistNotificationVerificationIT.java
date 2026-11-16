package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.service.NotificationService;
import com.aaa.aihealthcare.vo.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实专属MySQL验证药师消息生产、接收人边界、并发幂等及事务回滚。 */
class PharmacistNotificationVerificationIT extends MysqlVerificationSupport {
    @Autowired NotificationService notifications;
    @Autowired com.aaa.aihealthcare.dao.NotificationDao notificationDao;

    private void unpaidPrescription() {
        slot(100, 5, 1); medicine(5);
        order(200, 10, 100, "pending_rx_pay", true, 0);
        paidPrescription(300, 200, 10, 1);
        jdbc.update("UPDATE prescription SET status='unpaid',pay_method=NULL,pay_time=NULL WHERE id=300");
    }

    private long messages(long user, String type) {
        return count("SELECT COUNT(*) FROM user_notification WHERE user_id=? AND type=?", user, type);
    }

    @Test void balancePaymentNotifiesOnlyEligiblePharmacistsAndKeepsOtherRecipients() {
        unpaidPrescription();
        addUser(4, "PHARMACIST", 2L, "0.00");
        addUser(5, "PHARMACIST", 2L, "0.00"); jdbc.update("UPDATE sys_user SET status=0 WHERE id=5");
        addUser(6, "PHARMACIST", 2L, "0.00"); jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=6");
        addUser(7, "PHARMACIST", 1L, "0.00");
        jdbc.update("INSERT INTO doctor(id,department_id,name,role_type,status) VALUES (3,2,'Synthetic disabled','PHARMACIST',0),(4,1,'Synthetic wrong department','PHARMACIST',1)");
        addUser(8, "PHARMACIST", 3L, "0.00"); addUser(9, "PHARMACIST", 4L, "0.00");
        jdbc.update("INSERT INTO department(id,name,type,status) VALUES (3,'Synthetic inactive pharmacy','PHARMACY',0)");
        jdbc.update("INSERT INTO doctor(id,department_id,name,role_type,status) VALUES (5,3,'Synthetic inactive department','PHARMACIST',1)");
        addUser(30, "PHARMACIST", 5L, "0.00");
        addUser(31, "PHARMACIST", null, "0.00");

        patient.payPrescription(10L, 300L, new PaymentRequest("balance"));
        assertEquals(1, messages(3, "prescription_paid"));
        assertEquals(1, messages(4, "prescription_paid"));
        for (long id : List.of(1L,5L,6L,7L,8L,9L,30L,31L)) assertEquals(0, messages(id, "prescription_paid"));
        assertEquals(1, messages(2, "prescription_paid"));
        assertEquals(1, messages(10, "prescription_paid"));
        var message = notifications.page(3L, false, 1, 10).getList().getFirst();
        assertEquals("prescription", message.getBizType()); assertEquals(300L, message.getBizId());
        assertFalse(message.isRead()); assertEquals(1, notifications.unreadCount(3L));
    }

    @Test void twelveDuplicateBalancePaymentsSendOneMessagePerPharmacist() throws Exception {
        unpaidPrescription(); addUser(4, "PHARMACIST", 2L, "0.00");
        var outcomes = race(THREADS, i -> patient.payPrescription(10L, 300L, new PaymentRequest("balance")));
        assertTrue(outcomes.stream().allMatch(Outcome::success));
        assertEquals(1, messages(3, "prescription_paid")); assertEquals(1, messages(4, "prescription_paid"));
        assertEquals(new BigDecimal("90.00"), balance(10));
        assertEquals(1, count("SELECT COUNT(*) FROM balance_log WHERE type='prescription_pay'"));
    }

    @Test void alipayPaymentAlsoNotifiesPharmacistsWithoutChangingWalletBalance() {
        unpaidPrescription();
        patient.payPrescription(10L, 300L, new PaymentRequest("alipay"));
        patient.payPrescription(10L, 300L, new PaymentRequest("alipay"));
        assertEquals(1, messages(3, "prescription_paid"));
        assertEquals(new BigDecimal("100.00"), balance(10));
    }

    @Test void dispensingNotifiesSharedQueuePatientAndPrescribingDoctorOnlyOnce() throws Exception {
        unpaidPrescription(); addUser(4, "PHARMACIST", 2L, "0.00");
        patient.payPrescription(10L, 300L, new PaymentRequest("balance"));
        var outcomes = race(THREADS, i -> { consultation.dispenseAsPharmacist(300L); return true; });
        assertEquals(1, outcomes.stream().filter(Outcome::success).count());
        assertTrue(outcomes.stream().allMatch(o -> o.success() || o.businessRejected()));
        for (long user : List.of(2L,3L,4L,10L)) assertEquals(1, messages(user, "prescription_dispensed"));
        assertEquals(4, count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals("dispensed", status("prescription",300));
    }

    @Test void pharmacistsReadAndMarkOnlyTheirOwnMessages() {
        unpaidPrescription(); addUser(4, "PHARMACIST", 2L, "0.00");
        patient.payPrescription(10L, 300L, new PaymentRequest("balance"));
        var mine = notifications.page(3L, true, 1, 10).getList();
        long other = notifications.page(4L, true, 1, 10).getList().getFirst().getId();
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> notifications.markRead(3L,other));
        assertEquals(403,error.getStatusCode().value());
        notifications.markRead(3L,mine.getFirst().getId()); notifications.markRead(3L,mine.getFirst().getId());
        assertEquals(0, notifications.unreadCount(3L)); assertEquals(1, notifications.unreadCount(4L));
        assertEquals(1,notifications.markAllRead(4L)); assertEquals(0,notifications.markAllRead(4L));
    }

    @Test void pharmacistInsertFailureRollsBackPaymentLedgerAndAllNotifications() {
        unpaidPrescription();
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(patient);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"notifications",PharmacistNotificationFaults.armAtPharmacistInsert(notificationDao,jdbc));
        try { assertSqlFault(() -> patient.payPrescription(10L,300L,new PaymentRequest("balance"))); }
        finally { org.springframework.test.util.ReflectionTestUtils.setField(target,"notifications",notificationDao); jdbc.update("DELETE FROM _verification_fault"); }
        assertEquals("unpaid",status("prescription",300)); assertEquals("pending_rx_pay",status("appointment",200));
        assertEquals(new BigDecimal("100.00"),balance(10));
        assertEquals(0,count("SELECT COUNT(*) FROM balance_log")); assertEquals(0,count("SELECT COUNT(*) FROM user_notification"));
        patient.payPrescription(10L,300L,new PaymentRequest("balance"));
        assertEquals(1,messages(3,"prescription_paid"));
    }

    @Test void pharmacistInsertFailureRollsBackDispensingStockAndPatientNotification() {
        unpaidPrescription(); patient.payPrescription(10L,300L,new PaymentRequest("balance"));
        long before = count("SELECT COUNT(*) FROM user_notification");
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(notifications);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"notificationDao",PharmacistNotificationFaults.armAtPharmacistInsert(notificationDao,jdbc));
        try { assertSqlFault(() -> consultation.dispenseAsPharmacist(300L)); }
        finally { org.springframework.test.util.ReflectionTestUtils.setField(target,"notificationDao",notificationDao); jdbc.update("DELETE FROM _verification_fault"); }
        assertEquals("paid",status("prescription",300)); assertEquals("pending_pickup",status("appointment",200));
        assertEquals(5,count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals(before,count("SELECT COUNT(*) FROM user_notification"));
        consultation.dispenseAsPharmacist(300L); assertEquals(1,messages(3,"prescription_dispensed"));
    }

    private void assertSqlFault(Runnable action) {
        DataAccessException error = assertThrows(DataAccessException.class,action::run);
        Throwable cause=error;
        while (cause!=null && !(cause instanceof SQLException)) cause=cause.getCause();
        assertInstanceOf(SQLException.class,cause);
        assertEquals("45000",((SQLException)cause).getSQLState());
        assertTrue(cause.getMessage().contains("verification notification failure"));
    }
}
