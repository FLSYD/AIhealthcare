package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.entity.Appointment;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.vo.AppointmentCreateRequest;
import com.aaa.aihealthcare.vo.PaymentRequest;
import com.aaa.aihealthcare.vo.PrescriptionRequest;
import com.aaa.aihealthcare.vo.RechargeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RealMysqlConcurrencyVerificationIT extends MysqlVerificationSupport {
    private final PaymentRequest balancePayment = new PaymentRequest("balance");

    @Test
    @DisplayName("隔离证明：真实事务代理及测试账号不能读取业务库")
    void usesRealTransactionsAndCannotReadBusinessDatabase() {
        assertTrue(AopUtils.isAopProxy(patient));
        assertTrue(AopUtils.isAopProxy(consultation));
        assertTrue(jdbc.queryForObject("SELECT @@version", String.class).startsWith("8."));
        assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        assertThrows(DataAccessException.class, () -> jdbc.queryForObject("SELECT COUNT(*) FROM aihealthcare_local.sys_user", Long.class));
        printEvidence("isolation", jdbc.queryForMap("SELECT DATABASE() schema_name,CURRENT_USER() account_name,@@version mysql_version"));
    }

    @Test
    @DisplayName("12连接竞争最后号源：重复3轮只允许1单成功")
    void lastQuotaCannotBeOversold() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) resetSyntheticDatabase();
            slot(100, 1, 0);
            List<Outcome> outcomes = race(THREADS, i -> patient.create(10L + i,
                    new AppointmentCreateRequest(100L, "Synthetic " + i, "13800000000", "")));
            assertEquals(1, outcomes.stream().filter(Outcome::success).count());
            assertTrue(outcomes.stream().allMatch(result -> result.success() || result.businessRejected()), errors(outcomes));
            assertEquals(1, count("SELECT COUNT(*) FROM appointment"));
            assertEquals(1, count("SELECT booked_count FROM schedule WHERE id=100"));
            printEvidence("last-quota", "round=" + round + ",threads=" + THREADS + ",connections=" + connectionIds.size() + ",orders=1");
        }
    }

    @Test
    @DisplayName("相同患者并发建单：返回同一订单而非重复占号")
    void duplicateBookingReusesSameUnpaidOrder() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) resetSyntheticDatabase();
            slot(100, 10, 0);
            List<Outcome> outcomes = race(THREADS, i -> patient.create(10L,
                    new AppointmentCreateRequest(100L, "Same synthetic patient", "13800000000", "")));
            assertTrue(outcomes.stream().allMatch(Outcome::success), errors(outcomes));
            Set<Long> ids = outcomes.stream().map(result -> ((Appointment) result.value()).getId()).collect(Collectors.toSet());
            assertEquals(1, ids.size());
            assertEquals(1, count("SELECT COUNT(*) FROM appointment"));
            assertEquals(1, count("SELECT booked_count FROM schedule WHERE id=100"));
            printEvidence("duplicate-booking", "round=" + round + ",connections=" + connectionIds.size() + ",distinctOrders=" + ids.size());
        }
    }

    @Test
    @DisplayName("相同幂等键并发充值：只入账一次")
    void rechargeSameKeyCreditsOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) resetSyntheticDatabase();
            List<Outcome> outcomes = race(THREADS, i -> patient.recharge(10L,
                    new RechargeRequest(new BigDecimal("50.00"), "wechat"), "same-synthetic-operation"));
            assertTrue(outcomes.stream().allMatch(Outcome::success), errors(outcomes));
            assertEquals(new BigDecimal("150.00"), balance(10));
            assertEquals(1, count("SELECT COUNT(*) FROM balance_log WHERE type='recharge'"));
            printEvidence("recharge-same-key", "round=" + round + ",requests=" + THREADS + ",credits=1,balance=" + balance(10));
        }
    }

    @Test
    @DisplayName("修复F02：不带幂等键的12次提交全部拒绝且不入账")
    void rechargeWithoutKeyNeverCredits() throws Exception {
        List<Outcome> outcomes = race(THREADS, i -> patient.recharge(10L,
                new RechargeRequest(new BigDecimal("50.00"), "wechat"), null));
        assertTrue(outcomes.stream().allMatch(result -> result.error() instanceof IllegalArgumentException
                && result.error().getMessage().contains("充值请求标识")), errors(outcomes));
        assertEquals(new BigDecimal("100.00"), balance(10));
        assertEquals(0, count("SELECT COUNT(*) FROM balance_log WHERE type='recharge'"));
        printEvidence("F02-no-key", "requests=" + THREADS + ",credits=0,balance=" + balance(10));
    }

    @Test
    @DisplayName("相同充值键不同金额：拒绝第二种请求")
    void sameKeyCannotBeReusedForDifferentAmounts() throws Exception {
        List<Outcome> outcomes = race(2, i -> patient.recharge(10L,
                new RechargeRequest(new BigDecimal(i == 0 ? "20.00" : "50.00"), "wechat"), "one-key"));
        assertEquals(1, outcomes.stream().filter(Outcome::success).count());
        assertTrue(outcomes.stream().allMatch(result -> result.success() || result.businessRejected()), errors(outcomes));
        assertEquals(1, count("SELECT COUNT(*) FROM balance_log"));
        assertTrue(Set.of(new BigDecimal("120.00"), new BigDecimal("150.00")).contains(balance(10)));
    }

    @Test
    @DisplayName("同一预约12次并发付款：只扣款和记账一次")
    void sameAppointmentIsChargedOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) resetSyntheticDatabase();
            slot(100, 1, 1); order(200, 10, 100, "unpaid", false, 0);
            List<Outcome> outcomes = race(THREADS, i -> patient.payAppointment(10L, 200L, balancePayment));
            assertTrue(outcomes.stream().allMatch(Outcome::success), errors(outcomes));
            assertEquals(new BigDecimal("80.00"), balance(10));
            assertEquals(1, count("SELECT COUNT(*) FROM balance_log WHERE type='order_pay'"));
            assertEquals("pending", status("appointment", 200));
            printEvidence("same-order-payment", "round=" + round + ",requests=" + THREADS + ",chargeCount=1,balance=" + balance(10));
        }
    }

    @Test
    @DisplayName("同一余额竞争多笔不同订单：不足余额不得透支")
    void independentOrdersCannotOverdrawSameBalance() throws Exception {
        slot(100, THREADS, THREADS);
        jdbc.update("UPDATE sys_user SET balance=30 WHERE id=10");
        for (int i = 0; i < THREADS; i++) order(200 + i, 10, 100, "unpaid", false, 0);
        List<Outcome> outcomes = race(THREADS, i -> patient.payAppointment(10L, 200L + i, balancePayment));
        assertEquals(1, outcomes.stream().filter(Outcome::success).count());
        assertTrue(outcomes.stream().allMatch(result -> result.success() || result.businessRejected()), errors(outcomes));
        assertEquals(new BigDecimal("10.00"), balance(10));
        assertEquals(1, count("SELECT COUNT(*) FROM balance_log WHERE type='order_pay'"));
        assertEquals(1, count("SELECT COUNT(*) FROM appointment WHERE status='pending'"));
        printEvidence("balance-contention", "requests=" + THREADS + ",paid=1,balance=10.00");
    }

    @Test
    @DisplayName("付款与取消竞争：最终取消、号源归还且余额守恒")
    void paymentAndCancellationPreserveMoneyAndQuota() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) resetSyntheticDatabase();
            slot(100, 1, 1); order(200, 10, 100, "unpaid", false, 0);
            List<Outcome> outcomes = race(2, i -> i == 0 ? patient.payAppointment(10L, 200L, balancePayment) : patient.cancel(10L, 200L));
            assertTrue(outcomes.get(1).success(), errors(outcomes));
            assertTrue(outcomes.stream().allMatch(result -> result.success() || result.businessRejected()), errors(outcomes));
            assertEquals("cancelled", status("appointment", 200));
            assertEquals(new BigDecimal("100.00"), balance(10));
            assertEquals(0, count("SELECT booked_count FROM schedule WHERE id=100"));
            assertEquals(0, jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM balance_log", BigDecimal.class).compareTo(BigDecimal.ZERO));
            printEvidence("payment-cancel", "round=" + round + ",balance=100.00,booked=0,status=cancelled");
        }
    }

    @Test
    @DisplayName("过期回收与付款竞争：过期单不能付款")
    void expiredOrderCannotBePaidDuringExpirationRace() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) resetSyntheticDatabase();
            slot(100, 1, 1); order(200, 10, 100, "unpaid", false, 16);
            List<Outcome> outcomes = race(2, i -> i == 0 ? patient.payAppointment(10L, 200L, balancePayment) : appointments.expireUnpaid(200L));
            assertFalse(outcomes.get(0).success());
            assertTrue(outcomes.get(0).businessRejected(), errors(outcomes));
            assertTrue(outcomes.get(1).success(), errors(outcomes));
            assertEquals("cancelled", status("appointment", 200));
            assertEquals(new BigDecimal("100.00"), balance(10));
            assertEquals(0, count("SELECT booked_count FROM schedule WHERE id=100"));
            assertEquals(0, count("SELECT COUNT(*) FROM balance_log"));
        }
    }

    @Test
    @DisplayName("未过期订单回收与付款竞争：正常付款不被误取消")
    void freshOrderSurvivesExpirationRace() throws Exception {
        slot(100, 1, 1); order(200, 10, 100, "unpaid", false, 0);
        List<Outcome> outcomes = race(2, i -> i == 0 ? patient.payAppointment(10L, 200L, balancePayment) : appointments.expireUnpaid(200L));
        assertTrue(outcomes.stream().allMatch(Outcome::success), errors(outcomes));
        assertEquals(Boolean.FALSE, outcomes.get(1).value());
        assertEquals("pending", status("appointment", 200));
        assertEquals(new BigDecimal("80.00"), balance(10));
    }

    @Test
    @DisplayName("同一已付款处方并发发药：库存只能扣一次")
    void repeatedDispenseCannotDeductTwice() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            if (round > 0) resetSyntheticDatabase();
            slot(100, 1, 1); order(200, 10, 100, "pending_pickup", true, 0);
            medicine(5); paidPrescription(300, 200, 10, 2);
            List<Outcome> outcomes = race(THREADS, i -> { consultation.dispenseAsPharmacist(300L); return true; });
            assertEquals(1, outcomes.stream().filter(Outcome::success).count());
            assertTrue(outcomes.stream().allMatch(result -> result.success() || result.businessRejected()), errors(outcomes));
            assertEquals(3, count("SELECT stock FROM medicine WHERE id=1"));
            assertEquals("dispensed", status("prescription", 300));
            assertEquals("completed", status("appointment", 200));
            printEvidence("same-prescription-dispense", "round=" + round + ",success=1,stock=3");
        }
    }

    @Test
    @DisplayName("契约F01：两张处方购买最后一份库存后不应留下已收款无法发药订单")
    void paidPrescriptionsMustRemainDeliverable() throws Exception {
        slot(100, 2, 2); medicine(1);
        for (long id = 200; id <= 201; id++) {
            long user = id - 190;
            order(id, user, 100, "in_consultation", true, 0);
            jdbc.update("INSERT INTO consultation_record(appointment_id,doctor_id,chief_complaint,diagnosis) VALUES (?,1,'Synthetic complaint','Synthetic diagnosis')", id);
        }
        PrescriptionRequest.Item item = new PrescriptionRequest.Item();
        item.setMedicineId(1L); item.setQuantity(1); item.setDosage("Synthetic dosage"); item.setFrequency("daily"); item.setDays(1);
        PrescriptionRequest request = new PrescriptionRequest(); request.setItems(List.of(item));
        List<Prescription> created = new java.util.ArrayList<>();
        for (long orderId : List.of(200L, 201L)) {
            try { created.add(consultation.prescribe(doctorUser(), orderId, request)); }
            catch (IllegalArgumentException rejection) {
                assertTrue(rejection.getMessage().contains("库存"), "仅允许缺货导致的正常拒绝");
            }
        }
        assertFalse(created.isEmpty(), "库存足够一张处方，至少应能完成一张");
        List<Outcome> payments;
        if (created.size() > 1) {
            payments = race(created.size(), i -> patient.payPrescription(created.get(i).getUserId(), created.get(i).getId(), balancePayment));
        } else {
            Prescription rx = created.get(0);
            payments = List.of(new Outcome(patient.payPrescription(rx.getUserId(), rx.getId(), balancePayment), null));
        }
        assertTrue(payments.stream().allMatch(result -> result.success() || result.businessRejected()), errors(payments));
        List<Long> paid = jdbc.queryForList("SELECT id FROM prescription WHERE status='paid' ORDER BY id", Long.class);
        assertFalse(paid.isEmpty(), "至少一张有库存的处方应能付款交付");
        List<Outcome> dispensing;
        if (paid.size() > 1) {
            dispensing = race(paid.size(), i -> { consultation.dispenseAsPharmacist(paid.get(i)); return true; });
        } else {
            consultation.dispenseAsPharmacist(paid.get(0));
            dispensing = List.of(new Outcome(true, null));
        }
        assertTrue(dispensing.stream().allMatch(result -> result.success() || result.businessRejected()), errors(dispensing));
        long stranded = count("SELECT COUNT(*) FROM prescription WHERE status='paid'");
        BigDecimal netDebit = new BigDecimal("200.00").subtract(balance(10)).subtract(balance(11));
        BigDecimal deliveredAmount = jdbc.queryForObject("SELECT COALESCE(SUM(total_amount),0) FROM prescription WHERE status='dispensed'", BigDecimal.class);
        long stock = count("SELECT stock FROM medicine WHERE id=1");
        long deliveredQuantity = count("SELECT COALESCE(SUM(i.quantity),0) FROM prescription_item i JOIN prescription p ON p.id=i.prescription_id WHERE p.status='dispensed'");
        printEvidence("F01-stock-delivery", "payments=" + payments.stream().filter(Outcome::success).count()
                + ",dispensed=" + dispensing.stream().filter(Outcome::success).count()
                + ",strandedPaid=" + stranded + ",stock=" + stock + ",netDebit=" + netDebit
                + ",deliveredAmount=" + deliveredAmount + ",balances=" + balance(10) + "/" + balance(11));
        assertAll(
                () -> assertEquals(1L, deliveredQuantity, "最后一份库存应实际交付，不能以全部取消退款代替"),
                () -> assertEquals(0, stranded, "不应留下已收款未能交付且无退款的处方"),
                () -> assertEquals(0, netDebit.compareTo(deliveredAmount), "净扣款必须与已交付药品金额一致"),
                () -> assertTrue(stock >= 0),
                () -> assertEquals(1L, stock + deliveredQuantity, "库存与实际发药数量必须守恒")
        );
    }

    @Test
    @DisplayName("真实SQL故障注入：扣余额后的流水失败必须完整回滚")
    void paymentLedgerFailureRollsBackBalanceAndOrder() {
        slot(100, 1, 1); order(200, 10, 100, "unpaid", false, 0);
        jdbc.update("INSERT INTO _verification_fault VALUES ('balance_log')");
        try {
            assertInjectedFailure(() -> patient.payAppointment(10L, 200L, balancePayment), "verification balance-log failure");
        } finally { jdbc.update("DELETE FROM _verification_fault"); }
        assertEquals(new BigDecimal("100.00"), balance(10));
        assertEquals("unpaid", status("appointment", 200));
        assertEquals(0, count("SELECT COUNT(*) FROM balance_log"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification"));
        printEvidence("payment-rollback", "balance=100.00,status=unpaid,ledger=0,notifications=0");
    }

    @Test
    @DisplayName("真实SQL故障注入：退款流水失败必须撤销取消和退款")
    void refundFailureRollsBackCancellation() {
        slot(100, 1, 1); order(200, 10, 100, "pending", true, 0);
        jdbc.update("UPDATE sys_user SET balance=80 WHERE id=10");
        jdbc.update("INSERT INTO _verification_fault VALUES ('balance_log')");
        try { assertInjectedFailure(() -> patient.cancel(10L, 200L), "verification balance-log failure"); }
        finally { jdbc.update("DELETE FROM _verification_fault"); }
        assertEquals(new BigDecimal("80.00"), balance(10));
        assertEquals("pending", status("appointment", 200));
        assertEquals(1, count("SELECT booked_count FROM schedule WHERE id=100"));
        assertEquals(0, count("SELECT COUNT(*) FROM balance_log"));
    }

    @Test
    @DisplayName("真实SQL故障注入：扣库存后的状态写入失败必须还原库存")
    void dispenseFailureRollsBackInventoryAndState() {
        slot(100, 1, 1); order(200, 10, 100, "pending_pickup", true, 0);
        medicine(5); paidPrescription(300, 200, 10, 2);
        jdbc.update("INSERT INTO _verification_fault VALUES ('dispense')");
        try { assertInjectedFailure(() -> consultation.dispenseAsPharmacist(300L), "verification dispense failure"); }
        finally { jdbc.update("DELETE FROM _verification_fault"); }
        assertEquals(5, count("SELECT stock FROM medicine WHERE id=1"));
        assertEquals("paid", status("prescription", 300));
        assertEquals("pending_pickup", status("appointment", 200));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification"));
        printEvidence("dispense-rollback", "stock=5,prescription=paid,appointment=pending_pickup");
    }

    @Test
    @DisplayName("真实SQL故障注入：成功消息失败必须回滚建单和占号")
    void notificationFailureRollsBackBooking() {
        slot(100, 1, 0);
        jdbc.update("INSERT INTO _verification_fault VALUES ('notification')");
        try { assertInjectedFailure(() -> patient.create(10L, new AppointmentCreateRequest(100L, "Synthetic", "13800000000", "")), "verification notification failure"); }
        finally { jdbc.update("DELETE FROM _verification_fault"); }
        assertEquals(0, count("SELECT COUNT(*) FROM appointment"));
        assertEquals(0, count("SELECT booked_count FROM schedule WHERE id=100"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_notification"));
    }

    private void assertInjectedFailure(org.junit.jupiter.api.function.Executable operation, String expectedMessage) {
        DataAccessException error = assertThrows(DataAccessException.class, operation);
        Throwable cause = error;
        while (cause != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
        assertNotNull(cause, "必须确认为真实SQL故障，而非其他异常");
        java.sql.SQLException sql = (java.sql.SQLException) cause;
        assertEquals("45000", sql.getSQLState());
        assertTrue(sql.getMessage().contains(expectedMessage), "没有命中指定故障触发器");
    }

    private String errors(List<Outcome> outcomes) {
        return outcomes.stream().filter(result -> result.error() != null)
                .map(result -> result.error().getClass().getSimpleName() + ":" + result.error().getMessage())
                .collect(Collectors.joining(";"));
    }
}
