package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(IsolatedMysqlConfiguration.class)
@EnabledIfSystemProperty(named = "healthcare.verify.enabled", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
abstract class MysqlVerificationSupport {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected PatientCommandService patient;
    @Autowired protected AppointmentService appointments;
    @Autowired protected DoctorConsultationService consultation;
    @Autowired protected ScheduleService schedules;
    @Autowired protected SysUserService users;
    @Autowired protected DoctorService doctors;
    @Autowired protected DepartmentService departments;
    @Autowired protected DashboardService dashboard;
    @Autowired protected LoginService login;

    protected static final String SYNTHETIC_PASSWORD_HASH = new BCryptPasswordEncoder().encode("Synthetic-Test-Only-Password");
    protected static final int THREADS = 12;
    protected static final int ROUNDS = Integer.getInteger("healthcare.verify.rounds", 3);
    protected final Set<Long> connectionIds = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean DATABASE_POISONED = new AtomicBoolean();
    private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger();

    protected static final class VerificationInfrastructureException extends RuntimeException {
        VerificationInfrastructureException(String message) { super(message); }
        VerificationInfrastructureException(String message, Throwable cause) { super(message, cause); }
    }

    @BeforeEach
    void resetSyntheticDatabase() {
        assertFalse(DATABASE_POISONED.get(), "上轮工作线程未终止，禁止再执行任何清库SQL；须启动新的验证JVM");
        assertEquals(0, ACTIVE_WORKERS.get(), "仍有活动工作线程，禁止清库");
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(schema);
        assertTrue(schema.matches("aihealthcare_verify_[0-9]{8}_[a-f0-9]{8}"), "重置只允许专属验证库");
        jdbc.update("DELETE FROM _verification_fault");
        if (count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='wechat_native_payment'")>0) {
            jdbc.update("DELETE FROM wechat_native_payment");
        }
        for (String table : List.of("appointment_review", "balance_log", "consultation_record",
                "prescription_item", "prescription", "user_notification", "appointment", "schedule",
                "sys_login_log", "sys_operation_log", "sys_menu", "sys_role", "sys_user", "doctor", "medicine", "department")) {
            jdbc.update("DELETE FROM " + table);
        }
        connectionIds.clear();
        jdbc.update("INSERT INTO department(id,name,type,status) VALUES (1,'Synthetic clinical','CLINICAL',1),(2,'Synthetic pharmacy','PHARMACY',1)");
        jdbc.update("INSERT INTO doctor(id,department_id,name,role_type,status) VALUES (1,1,'Synthetic doctor','DOCTOR',1),(2,2,'Synthetic pharmacist','PHARMACIST',1)");
        addUser(1, "ADMIN", null, "0.00");
        addUser(2, "DOCTOR", 1L, "0.00");
        addUser(3, "PHARMACIST", 2L, "0.00");
        for (long id = 10; id < 10 + THREADS; id++) addUser(id, "USER", null, "100.00");
    }

    protected void addUser(long id, String role, Long doctorId, String balance) {
        jdbc.update("INSERT INTO sys_user(id,username,password,nickname,role,doctor_id,balance,status,deleted) VALUES (?,?,?,?,?,?,?,1,0)",
                id, "synthetic_" + id, SYNTHETIC_PASSWORD_HASH, "Synthetic " + id, role, doctorId, new BigDecimal(balance));
    }

    protected void slot(long id, int quota, int booked) {
        jdbc.update("INSERT INTO schedule(id,doctor_id,schedule_date,time_slot,total_quota,booked_count,status) VALUES (?,1,?,'09:00',?,?,1)",
                id, LocalDate.now().plusDays(1), quota, booked);
    }

    protected void order(long id, long userId, long slotId, String status, boolean paid, int ageMinutes) {
        jdbc.update("INSERT INTO appointment(id,user_id,schedule_id,patient_name,patient_phone,amount,status,pay_method,pay_time,create_time) VALUES (?,?,?,'Synthetic patient','13800000000',20.00,?,?,?,?)",
                id, userId, slotId, status, paid ? "balance" : null,
                paid ? Timestamp.valueOf(LocalDateTime.now()) : null,
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(ageMinutes)));
    }

    protected void medicine(int stock) {
        jdbc.update("INSERT INTO medicine(id,name,unit,price,stock,status) VALUES (1,'Synthetic medicine','unit',10.00,?,1)", stock);
    }

    protected void paidPrescription(long id, long orderId, long userId, int quantity) {
        jdbc.update("INSERT INTO prescription(id,appointment_id,user_id,doctor_id,total_amount,status,pay_method,pay_time) VALUES (?,?,?,1,?,'paid','balance',NOW())",
                id, orderId, userId, new BigDecimal("10.00").multiply(BigDecimal.valueOf(quantity)));
        jdbc.update("INSERT INTO prescription_item(prescription_id,medicine_id,medicine_name,quantity,unit_price,amount) VALUES (?,1,'Synthetic medicine',?,10.00,?)",
                id, quantity, new BigDecimal("10.00").multiply(BigDecimal.valueOf(quantity)));
    }

    protected SysUser doctorUser() {
        SysUser user = new SysUser();
        user.setId(2L); user.setUsername("synthetic_2"); user.setRole("DOCTOR");
        user.setDoctorId(1L); user.setStatus(1); user.setDeleted(0);
        return user;
    }

    protected long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }

    protected BigDecimal balance(long id) {
        return jdbc.queryForObject("SELECT balance FROM sys_user WHERE id=?", BigDecimal.class, id);
    }

    protected String status(String table, long id) {
        if (!Set.of("appointment", "prescription").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id=?", String.class, id);
    }

    protected record Outcome(Object value, Throwable error) {
        boolean success() { return error == null; }
        boolean businessRejected() {
            if (!(error instanceof IllegalArgumentException || error instanceof IllegalStateException)) return false;
            String message = error.getMessage() == null ? "" : error.getMessage();
            return List.of("号源已满", "余额不足", "当前预约状态不能付款", "付款期限",
                    "同一充值标识", "请勿重复", "库存不足", "库存不够", "药品已停用")
                    .stream().anyMatch(message::contains);
        }
    }

    protected List<Outcome> race(int threads, IntFunction<Object> operation) throws Exception {
        connectionIds.clear();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean executionPermitted = new AtomicBoolean();
        List<Future<Outcome>> futures = new ArrayList<>();
        Throwable primaryFailure = null;
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ACTIVE_WORKERS.incrementAndGet();
                    try {
                        TransactionTemplate template = new TransactionTemplate(transactions);
                        Object result = template.execute(transaction -> {
                            connectionIds.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                            ready.countDown();
                            try {
                                if (!go.await(10, TimeUnit.SECONDS)) throw new VerificationInfrastructureException("启动屏障超时");
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new VerificationInfrastructureException("并发测试被中断", exception);
                            }
                            if (!executionPermitted.get()) throw new VerificationInfrastructureException("起跑前校验失败，不执行业务操作");
                            return operation.apply(index);
                        });
                        return new Outcome(result, null);
                    } catch (Throwable error) {
                        return new Outcome(null, error);
                    } finally {
                        ACTIVE_WORKERS.decrementAndGet();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发工作线程没有全部就绪");
            assertEquals(threads, connectionIds.size(), "放行业务前每个线程必须已持有独立连接");
            executionPermitted.set(true);
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) outcomes.add(future.get(35, TimeUnit.SECONDS));
            assertEquals(threads, connectionIds.size(), "放行前每个并发事务必须持有独立的真实 MySQL 连接");
            assertTrue(outcomes.stream().noneMatch(result -> result.error() instanceof VerificationInfrastructureException),
                    "测试设施异常不得计入正常业务拒绝");
            System.out.println("VERIFICATION_RACE threads=" + threads + ",connections=" + connectionIds.size()
                    + ",success=" + outcomes.stream().filter(Outcome::success).count()
                    + ",rejected=" + outcomes.stream().filter(result -> !result.success()).count());
            return outcomes;
        } catch (Exception | Error error) {
            primaryFailure = error;
            throw error;
        } finally {
            go.countDown();
            futures.forEach(future -> { if (!future.isDone()) future.cancel(true); });
            pool.shutdownNow();
            boolean terminated;
            try {
                terminated = pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                DATABASE_POISONED.set(true);
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(interrupted);
                    terminated = false;
                } else {
                    throw interrupted;
                }
            }
            if (!terminated || ACTIVE_WORKERS.get() != 0) {
                DATABASE_POISONED.set(true);
                AssertionError contamination = new AssertionError("工作线程未退出，已污染标记整个验证上下文，禁止后续清库");
                if (primaryFailure != null) primaryFailure.addSuppressed(contamination);
                else throw contamination;
            }
        }
    }

    protected void printEvidence(String name, Object evidence) {
        System.out.println("VERIFICATION_EVIDENCE " + name + " " + evidence);
    }
}
