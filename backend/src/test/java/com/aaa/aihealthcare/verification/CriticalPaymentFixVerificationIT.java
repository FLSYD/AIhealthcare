package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.entity.Medicine;
import com.aaa.aihealthcare.service.MedicineService;
import com.aaa.aihealthcare.vo.PaymentRequest;
import com.aaa.aihealthcare.vo.RechargeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 仅使用专属 MySQL 验证库，检查两项重要修复及其异常路径。 */
class CriticalPaymentFixVerificationIT extends MysqlVerificationSupport {
    @Autowired private MedicineService medicines;
    private final PaymentRequest payment = new PaymentRequest("balance");

    @Test
    @DisplayName("F01：12个患者竞争最后库存仅一人被收款且可实际取药")
    void lastStockIsReservedBeforeCharging() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) resetSyntheticDatabase();
            slot(100, THREADS, THREADS); medicine(1);
            for (int i = 0; i < THREADS; i++) unpaidPrescription(300 + i, 200 + i, 10 + i, 1);
            List<Outcome> outcomes = race(THREADS, i -> patient.payPrescription(10L + i, 300L + i, payment));
            assertEquals(1, outcomes.stream().filter(Outcome::success).count());
            assertTrue(outcomes.stream().allMatch(result -> result.success() || result.businessRejected()));
            assertEquals(1, reserved(1));
            assertEquals(1, count("SELECT stock FROM medicine WHERE id=1"));
            assertEquals(1, count("SELECT COUNT(*) FROM balance_log WHERE type='prescription_pay'"));
            assertEquals(new BigDecimal("1190.00"), jdbc.queryForObject(
                    "SELECT SUM(balance) FROM sys_user WHERE role='USER'", BigDecimal.class));
            long paid = count("SELECT id FROM prescription WHERE status='paid'");
            consultation.dispenseAsPharmacist(paid);
            assertEquals(0, reserved(1));
            assertEquals(0, count("SELECT stock FROM medicine WHERE id=1"));
            assertEquals(1, count("SELECT COUNT(*) FROM prescription WHERE status='dispensed'"));
            printEvidence("F01-fixed-last-stock", "round=" + round + ",connections=12,charged=1,delivered=1,strandedPaid=0");
        }
    }

    @Test
    @DisplayName("F01：旧RR快照不能忽略另一事务已提交的库存预留")
    void reservationUsesCurrentReadInsteadOfOldSnapshot() {
        slot(100, 2, 2); medicine(1);
        unpaidPrescription(300, 200, 10, 1);
        unpaidPrescription(301, 201, 11, 1);
        TransactionTemplate oldSnapshot = new TransactionTemplate(transactions);
        assertThrows(IllegalArgumentException.class, () -> oldSnapshot.execute(transaction -> {
            assertEquals(0, reserved(1));
            TransactionTemplate other = new TransactionTemplate(transactions);
            other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.execute(ignored -> patient.payPrescription(11L, 301L, payment));
            assertEquals(0, reserved(1), "当前事务刻意保留付款之前的旧快照");
            return patient.payPrescription(10L, 300L, payment);
        }));
        assertEquals(new BigDecimal("100.00"), balance(10));
        assertEquals(new BigDecimal("90.00"), balance(11));
        assertEquals(1, reserved(1));
        printEvidence("F01-current-read", "oldSnapshotReserved=0,committedReserved=1,rejectedWithoutDebit=true");
    }

    @Test
    @DisplayName("F01：多药处方任一药品缺货时全部拒收，补货后可完成")
    void partialShortageNeverChargesOrReservesOtherItems() {
        slot(100, 1, 1); medicine(2);
        unpaidPrescription(300, 200, 10, 1);
        jdbc.update("INSERT INTO medicine(id,name,unit,price,stock,status) VALUES (2,'Synthetic other','unit',10,0,1)");
        jdbc.update("INSERT INTO prescription_item(prescription_id,medicine_id,medicine_name,quantity,unit_price,amount) VALUES (300,2,'Synthetic other',1,10,10)");
        jdbc.update("UPDATE prescription SET total_amount=20 WHERE id=300");
        assertThrows(IllegalArgumentException.class, () -> patient.payPrescription(10L, 300L, payment));
        assertEquals(new BigDecimal("100.00"), balance(10));
        assertEquals("unpaid", status("prescription", 300));
        assertEquals(0, reserved(1));
        assertEquals(0, count("SELECT COUNT(*) FROM balance_log"));
        jdbc.update("UPDATE medicine SET stock=1 WHERE id=2");
        patient.payPrescription(10L, 300L, payment);
        consultation.dispenseAsPharmacist(300L);
        assertEquals(new BigDecimal("80.00"), balance(10));
        assertEquals(1, count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals(0, count("SELECT stock FROM medicine WHERE id=2"));
        assertEquals("dispensed", status("prescription", 300));
    }

    @Test
    @DisplayName("F01：相同处方重复付款不重复收款或预留，发药只扣一次实物")
    void repeatedPrescriptionPaymentReservesOnce() throws Exception {
        slot(100, 1, 1); medicine(5); unpaidPrescription(300, 200, 10, 2);
        List<Outcome> results = race(THREADS, i -> patient.payPrescription(10L, 300L, payment));
        assertTrue(results.stream().allMatch(Outcome::success));
        assertEquals(new BigDecimal("80.00"), balance(10));
        assertEquals(2, reserved(1));
        assertEquals(5, count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals(1, count("SELECT COUNT(*) FROM balance_log WHERE type='prescription_pay'"));
        consultation.dispenseAsPharmacist(300L);
        assertEquals(3, count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals(0, reserved(1));
        patient.payPrescription(10L, 300L, payment);
        assertEquals(new BigDecimal("80.00"), balance(10));
    }

    @Test
    @DisplayName("F01：处方付款流水SQL故障回滚扣款和预留")
    void prescriptionLedgerFailureRollsBackChargeAndReservation() {
        slot(100, 1, 1); medicine(1); unpaidPrescription(300, 200, 10, 1);
        injectedPaymentFailure("balance_log", "verification balance-log failure");
        assertUnpaidAndUntouched();
        patient.payPrescription(10L, 300L, payment);
        assertEquals(1, reserved(1));
    }

    @Test
    @DisplayName("F01：处方转paid后的通知SQL故障回滚预留和扣款")
    void notificationFailureAfterPaidReleasesReservation() {
        slot(100, 1, 1); medicine(1); unpaidPrescription(300, 200, 10, 1);
        injectedPaymentFailure("notification", "verification notification failure");
        assertUnpaidAndUntouched();
    }

    @Test
    @DisplayName("F01：库存调整和两个停用入口均不能破坏已付款预留")
    void managementCannotInvalidatePaidReservation() {
        slot(100, 1, 1); medicine(2); unpaidPrescription(300, 200, 10, 1);
        patient.payPrescription(10L, 300L, payment);
        Medicine request = medicines.getById(1L);
        request.setExpectedStock(2); request.setStock(0);
        assertThrows(IllegalArgumentException.class, () -> medicines.update(request));
        assertThrows(IllegalArgumentException.class, () -> medicines.updateStatus(1L, 0));
        Medicine disabled = medicines.getById(1L); disabled.setStatus(0);
        assertThrows(IllegalArgumentException.class, () -> medicines.update(disabled));
        assertThrows(IllegalArgumentException.class, () -> medicines.deleteById(1L));
        assertEquals(2, count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals(1, count("SELECT status FROM medicine WHERE id=1"));
        // 可以调整未占用的部分；实际取药结束后才允许停用。
        Medicine reduced = medicines.getById(1L); reduced.setExpectedStock(2); reduced.setStock(1);
        assertTrue(medicines.update(reduced));
        consultation.dispenseAsPharmacist(300L);
        assertTrue(medicines.updateStatus(1L, 0));
        assertEquals(0, count("SELECT stock FROM medicine WHERE id=1"));
    }

    @Test
    @DisplayName("F01：并发减库存与付款不能同时成功并破坏交付")
    void stockReductionRacePreservesReservation() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) resetSyntheticDatabase();
            slot(100, 1, 1); medicine(1); unpaidPrescription(300, 200, 10, 1);
            Medicine change = medicines.getById(1L); change.setExpectedStock(1); change.setStock(0);
            List<Outcome> results = race(2, i -> i == 0
                    ? patient.payPrescription(10L, 300L, payment) : medicines.update(change));
            assertEquals(1, results.stream().filter(Outcome::success).count());
            assertTrue(results.stream().allMatch(result -> result.success() || result.error() instanceof IllegalArgumentException));
            if ("paid".equals(status("prescription", 300))) {
                assertEquals(1, reserved(1));
                assertEquals(1, count("SELECT stock FROM medicine WHERE id=1"));
                consultation.dispenseAsPharmacist(300L);
                assertEquals(new BigDecimal("90.00"), balance(10));
            } else {
                assertEquals("unpaid", status("prescription", 300));
                assertEquals(new BigDecimal("100.00"), balance(10));
                assertEquals(0, reserved(1));
                assertEquals(0, count("SELECT stock FROM medicine WHERE id=1"));
            }
        }
    }

    @Test
    @DisplayName("F01：停用与付款竞争仅允许停用拒收或保留可发药库存")
    void disableRaceNeverStrandsPayment() throws Exception {
        slot(100, 1, 1); medicine(1); unpaidPrescription(300, 200, 10, 1);
        List<Outcome> results = race(2, i -> i == 0
                ? patient.payPrescription(10L, 300L, payment) : medicines.updateStatus(1L, 0));
        assertEquals(1, results.stream().filter(Outcome::success).count());
        assertTrue(results.stream().allMatch(result -> result.success() || result.error() instanceof IllegalArgumentException));
        if ("paid".equals(status("prescription", 300))) {
            assertEquals(1, count("SELECT status FROM medicine WHERE id=1"));
            consultation.dispenseAsPharmacist(300L);
            assertEquals(new BigDecimal("90.00"), balance(10));
        } else {
            assertEquals(new BigDecimal("100.00"), balance(10));
            assertEquals(0, count("SELECT status FROM medicine WHERE id=1"));
        }
    }

    @Test
    @DisplayName("F02：空白、超长幂等键全部拒收")
    void invalidRechargeKeysNeverCredit() {
        for (String key : List.of("", " ", "a".repeat(201))) {
            assertThrows(IllegalArgumentException.class, () -> patient.recharge(10L,
                    new RechargeRequest(new BigDecimal("50.00"), "wechat"), key));
        }
        assertEquals(new BigDecimal("100.00"), balance(10));
        assertEquals(0, count("SELECT COUNT(*) FROM balance_log"));
    }

    @Test
    @DisplayName("F02：旧RR快照重放已提交的充值键仍只入账一次")
    void rechargeReplayUsesCurrentRead() {
        RechargeRequest request = new RechargeRequest(new BigDecimal("50.00"), "wechat");
        TransactionTemplate oldSnapshot = new TransactionTemplate(transactions);
        BigDecimal result = oldSnapshot.execute(transaction -> {
            assertEquals(0, count("SELECT COUNT(*) FROM balance_log"));
            TransactionTemplate other = new TransactionTemplate(transactions);
            other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.execute(ignored -> patient.recharge(10L, request, "committed-before-old-snapshot-replay"));
            assertEquals(0, count("SELECT COUNT(*) FROM balance_log"), "刻意保留旧账本快照");
            return patient.recharge(10L, request, "committed-before-old-snapshot-replay");
        });
        assertEquals(new BigDecimal("150.00"), result);
        assertEquals(new BigDecimal("150.00"), balance(10));
        assertEquals(1, count("SELECT COUNT(*) FROM balance_log WHERE type='recharge'"));
        printEvidence("F02-current-read", "oldSnapshotCredits=0,actualCredits=1,balance=150.00");
    }

    @Test
    @DisplayName("F02：相同键按患者隔离，各患者只能入账一次")
    void rechargeKeysAreScopedToUser() throws Exception {
        List<Outcome> results = race(THREADS, i -> patient.recharge(10L + i % 2,
                new RechargeRequest(new BigDecimal("50.00"), "wechat"), "shared-synthetic-key"));
        assertTrue(results.stream().allMatch(Outcome::success));
        assertEquals(2, count("SELECT COUNT(*) FROM balance_log WHERE type='recharge'"));
        assertEquals(new BigDecimal("150.00"), balance(10));
        assertEquals(new BigDecimal("150.00"), balance(11));
    }

    @Test
    @DisplayName("F02：相同键不能用于不同支付渠道")
    void rechargeKeyCannotChangeChannel() throws Exception {
        List<Outcome> results = race(2, i -> patient.recharge(10L,
                new RechargeRequest(new BigDecimal("50.00"), i == 0 ? "wechat" : "alipay"), "one-channel-key"));
        assertEquals(1, results.stream().filter(Outcome::success).count());
        assertTrue(results.stream().allMatch(result -> result.success() || result.businessRejected()));
        assertEquals(new BigDecimal("150.00"), balance(10));
        assertEquals(1, count("SELECT COUNT(*) FROM balance_log WHERE type='recharge'"));
    }

    private void unpaidPrescription(long id, long appointmentId, long userId, int quantity) {
        order(appointmentId, userId, 100, "pending_rx_pay", true, 0);
        paidPrescription(id, appointmentId, userId, quantity);
        jdbc.update("UPDATE prescription SET status='unpaid',pay_method=NULL,pay_time=NULL WHERE id=?", id);
    }

    private long reserved(long medicineId) {
        return count("SELECT COALESCE(SUM(i.quantity),0) FROM prescription_item i JOIN prescription p ON p.id=i.prescription_id WHERE i.medicine_id=? AND p.status='paid'", medicineId);
    }

    private void injectedPaymentFailure(String fault, String message) {
        jdbc.update("INSERT INTO _verification_fault VALUES (?)", fault);
        try {
            DataAccessException exception = assertThrows(DataAccessException.class,
                    () -> patient.payPrescription(10L, 300L, payment));
            Throwable cause = exception;
            while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
            assertInstanceOf(SQLException.class, cause);
            assertEquals("45000", ((SQLException) cause).getSQLState());
            assertTrue(cause.getMessage().contains(message));
        } finally { jdbc.update("DELETE FROM _verification_fault"); }
    }

    private void assertUnpaidAndUntouched() {
        assertEquals(new BigDecimal("100.00"), balance(10));
        assertEquals("unpaid", status("prescription", 300));
        assertEquals("pending_rx_pay", status("appointment", 200));
        assertEquals(0, reserved(1));
        assertEquals(1, count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM balance_log"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification"));
    }
}
