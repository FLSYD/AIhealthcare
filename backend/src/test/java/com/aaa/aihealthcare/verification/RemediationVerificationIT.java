package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.Schedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 整改后真实数据库验收，所有数据均由父类在专属验证库中创建。 */
class RemediationVerificationIT extends MysqlVerificationSupport {
    @TempDir java.nio.file.Path avatarDirectory;
    @Autowired com.aaa.aihealthcare.dao.LoginDao loginDao;
    @Autowired com.aaa.aihealthcare.dao.SysUserDao sysUserDao;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test void avatarReferenceCommitAndCollectionUseSameDatabaseLockOrder() throws Exception {
        String name = "a".repeat(32) + ".png";
        var file = java.nio.file.Files.writeString(avatarDirectory.resolve(name), "synthetic");
        java.nio.file.Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(172800)));
        var task = new com.aaa.aihealthcare.service.impl.AvatarCleanupTask(loginDao, redis, sysUserDao, transactions);
        ReflectionTestUtils.setField(task, "avatarDirectory", avatarDirectory.toString());
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(doctors);
        Object oldDirectory = ReflectionTestUtils.getField(target, "avatarDirectory");
        ReflectionTestUtils.setField(target, "avatarDirectory", avatarDirectory.toString());
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var begun = new java.util.concurrent.CountDownLatch(1);
        var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                Doctor edit = person("Synthetic avatar doctor"); edit.setId(1L);
                edit.setAvatar("/api/avatar/"+name+"?v=1#photo");
                assertTrue(doctors.update(edit));
                assertEquals("/api/avatar/"+name, edit.getAvatar());
                future.set(pool.submit(() -> { begun.countDown(); task.cleanup(); }));
                try {
                    assertTrue(begun.await(2, java.util.concurrent.TimeUnit.SECONDS));
                    Thread.sleep(100);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                assertFalse(future.get().isDone(), "回收必须等待引用写入事务提交");
            });
            future.get().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(java.nio.file.Files.exists(file), "已提交且仍引用的头像不得被回收");
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            ReflectionTestUtils.setField(target, "avatarDirectory", oldDirectory);
        }
    }

    @Test void rebindingAnAlreadyCollectedAvatarIsRejected() throws Exception {
        String name = "b".repeat(32) + ".png";
        var file = java.nio.file.Files.writeString(avatarDirectory.resolve(name), "synthetic");
        java.nio.file.Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(172800)));
        var task = new com.aaa.aihealthcare.service.impl.AvatarCleanupTask(loginDao, redis, sysUserDao, transactions);
        ReflectionTestUtils.setField(task, "avatarDirectory", avatarDirectory.toString());
        task.cleanup(); assertFalse(java.nio.file.Files.exists(file));
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(doctors);
        Object oldDirectory = ReflectionTestUtils.getField(target, "avatarDirectory");
        ReflectionTestUtils.setField(target, "avatarDirectory", avatarDirectory.toString());
        try {
            Doctor edit = person("Synthetic missing avatar"); edit.setId(1L); edit.setAvatar("/api/avatar/"+name);
            assertThrows(IllegalArgumentException.class, () -> doctors.update(edit));
            assertNull(jdbc.queryForObject("SELECT avatar FROM doctor WHERE id=1", String.class));
        } finally { ReflectionTestUtils.setField(target, "avatarDirectory", oldDirectory); }
    }

    @Test void pharmacistDeleteUsesExistingSoftDeleteAndDisablesLastLinkedProfile() {
        assertEquals(1, users.delete(3L));
        assertEquals(1, count("SELECT deleted FROM sys_user WHERE id=3"));
        assertEquals(0, count("SELECT status FROM doctor WHERE id=2"));
        assertEquals(1, count("SELECT COUNT(*) FROM doctor WHERE id=2"));
    }

    @Test void pharmacistBatchDeleteKeepsProfileEnabledWhenAnotherActiveAccountRemains() {
        addUser(4L, "PHARMACIST", 2L, "0.00");
        assertEquals(1, users.batchDelete(List.of(3L)));
        assertEquals(1, count("SELECT status FROM doctor WHERE id=2"));
        assertEquals(1, users.batchDelete(List.of(4L)));
        assertEquals(0, count("SELECT status FROM doctor WHERE id=2"));
    }

    @Test void batchDeleteMissingAccountRollsBackPharmacistRemoval() {
        assertThrows(IllegalArgumentException.class, () -> users.batchDelete(List.of(3L, 9999L)));
        assertEquals(0, count("SELECT deleted FROM sys_user WHERE id=3"));
        assertEquals(1, count("SELECT status FROM doctor WHERE id=2"));
    }

    @Test void schedulesWithCompletedOrCancelledHistoryRemainProtected() {
        slot(100L, 5, 1);
        order(200L, 10L, 100L, "completed", true, 0);
        assertThrows(IllegalArgumentException.class, () -> schedules.deleteById(100L));
        jdbc.update("UPDATE appointment SET status='cancelled' WHERE id=200");
        jdbc.update("UPDATE schedule SET booked_count=0 WHERE id=100");
        assertThrows(IllegalArgumentException.class, () -> schedules.deleteById(100L));
        var result = schedules.page(1L, LocalDate.now(), LocalDate.now().plusDays(2), 1, 10);
        assertEquals(1L, result.getList().getFirst().getAppointmentCount());
        assertEquals(1, count("SELECT COUNT(*) FROM appointment WHERE id=200"));
        Schedule unused = schedule("10:00");
        assertTrue(schedules.save(unused));
        assertTrue(schedules.deleteById(unused.getId()));
    }

    @Test void departmentTypeChangeWithStaffIsRejectedAndOmittedTypeIsPreserved() {
        Department edit = new Department(); edit.setId(2L); edit.setName("Synthetic edited");
        assertTrue(departments.update(edit));
        assertEquals("PHARMACY", jdbc.queryForObject("SELECT type FROM department WHERE id=2", String.class));
        edit.setType("CLINICAL");
        assertThrows(IllegalArgumentException.class, () -> departments.update(edit));
        assertEquals("PHARMACY", jdbc.queryForObject("SELECT type FROM department WHERE id=2", String.class));
    }

    @Test void equivalentMorningTimeCannotCreateDuplicateSchedules() {
        Schedule a = schedule("上午");
        assertTrue(schedules.save(a));
        assertThrows(IllegalArgumentException.class, () -> schedules.save(schedule("09:00")));
        assertEquals(1, count("SELECT COUNT(*) FROM schedule"));
    }

    @Test void rechargeFilterRunsBeforePaginationAndStillEnforcesOwner() {
        for (int i=0; i<17; i++) {
            jdbc.update("INSERT INTO balance_log(user_id,type,amount,balance_after,pay_method,remark,create_time) VALUES (10,'recharge',1,100,'alipay','synthetic',DATE_SUB(NOW(), INTERVAL 1 DAY))");
        }
        for (int i=0; i<15; i++) {
            jdbc.update("INSERT INTO balance_log(user_id,type,amount,balance_after,pay_method,remark,create_time) VALUES (10,'order_pay',-1,100,'balance','synthetic',NOW())");
        }
        jdbc.update("INSERT INTO balance_log(user_id,type,amount,balance_after,pay_method,create_time) VALUES (11,'recharge',1,100,'alipay',NOW())");
        var first = patient.walletLogs(10L, 1, 10, "recharge");
        var second = patient.walletLogs(10L, 2, 10, "recharge");
        assertEquals(17, first.getTotal()); assertEquals(10, first.getRecords().size());
        assertEquals(7, second.getRecords().size());
        assertTrue(first.getRecords().stream().allMatch(row -> "recharge".equals(row.getType())));
        assertThrows(IllegalArgumentException.class, () -> patient.walletLogs(10L, 1, 10, "invalid"));
    }

    @Test void newEmployeesUse123AndCanChangeTheirOwnPassword() {
        String existingPassword = jdbc.queryForObject("SELECT password FROM sys_user WHERE id=2", String.class);
        Doctor a = person("Synthetic Alpha"); Doctor b = person("Synthetic Beta");
        b.setDepartmentId(2L);
        var first = doctors.createWithAccount(a); var second = doctors.createWithAccount(b);
        assertEquals("123", first.initialPassword());
        assertEquals("123", second.initialPassword());
        assertEquals(200, login.login(first.loginUsername(), first.initialPassword()).getCode());
        assertEquals(200, login.login(second.loginUsername(), second.initialPassword()).getCode());
        for (var employee : List.of(first, second)) {
            Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, employee.loginUsername());
            String stored = jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?", String.class, userId);
            assertNotEquals("123", stored, "初始密码仍应加密存储");
            assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("123", stored));
            var change = new com.aaa.aihealthcare.vo.PasswordChange();
            change.setOldPassword("123");
            change.setNewPassword("Synthetic-Changed-Password");
            change.setConfirmPassword("Synthetic-Changed-Password");
            assertEquals(200, login.changePassword(userId, change).getCode());
            assertNotEquals(200, login.login(employee.loginUsername(), "123").getCode());
            assertEquals(200, login.login(employee.loginUsername(), "Synthetic-Changed-Password").getCode());
        }
        assertEquals(existingPassword, jdbc.queryForObject("SELECT password FROM sys_user WHERE id=2", String.class));
        assertEquals(new BigDecimal("100.00"), balance(10L));
    }

    private Doctor person(String name) {
        Doctor doctor = new Doctor(); doctor.setName(name); doctor.setDepartmentId(1L); doctor.setStatus(1);
        return doctor;
    }

    private Schedule schedule(String time) {
        Schedule schedule = new Schedule(); schedule.setDoctorId(1L);
        schedule.setScheduleDate(LocalDate.now().plusDays(1)); schedule.setTimeSlot(time);
        schedule.setTotalQuota(5); schedule.setStatus(1); return schedule;
    }
}
