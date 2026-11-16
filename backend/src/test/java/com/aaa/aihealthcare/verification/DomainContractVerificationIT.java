package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.common.BookingTime;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.dao.LoginDao;
import com.aaa.aihealthcare.dao.MiniAuthDao;
import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.Schedule;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.impl.MiniAvatarServiceImpl;
import com.aaa.aihealthcare.vo.MiniAvatarBindRequest;
import com.aaa.aihealthcare.vo.ScheduleBatchRequest;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 对应已报告问题的期望业务契约。问题未修复时，这些用例应明确失败；
 * 不通过反向断言把缺陷包装为正常行为。
 */
class DomainContractVerificationIT extends MysqlVerificationSupport {
    @Autowired DataSource dataSource;
    @Autowired MiniAuthDao miniAuthDao;
    @Autowired com.aaa.aihealthcare.service.impl.AvatarWriteExecutor avatarWriter;
    @TempDir Path avatarDirectory;

    @Test
    @DisplayName("契约F04：医生账号不能绑定药师档案")
    void doctorAccountCannotBindPharmacist() {
        SysUser user = new SysUser();
        user.setUsername("synthetic_mismatch"); user.setPassword("Synthetic-Only");
        user.setRole("DOCTOR"); user.setDoctorId(2L); user.setStatus(1);
        try { users.insert(user); }
        catch (IllegalArgumentException expectedRejection) { /* 拒绝后仍验证持久化状态。 */ }
        long mismatches = count("SELECT COUNT(*) FROM sys_user u JOIN doctor d ON d.id=u.doctor_id WHERE u.role IN ('DOCTOR','PHARMACIST') AND u.role<>d.role_type");
        printEvidence("F04-account-role", "mismatchedRows=" + mismatches);
        assertEquals(0, mismatches, "接口接受了医生账号绑定药师档案");
    }

    @Test
    @DisplayName("契约F04：变更人员类型必须拒绝或同步关联账号")
    void staffTypeChangeCannotLeaveAccountRoleMismatch() {
        Doctor edit = new Doctor(); edit.setId(1L); edit.setName("Synthetic changed");
        edit.setDepartmentId(2L); edit.setRoleType("PHARMACIST"); edit.setStatus(1);
        try { doctors.update(edit); }
        catch (IllegalArgumentException expectedRejection) { /* 拒绝后仍验证持久化状态。 */ }
        assertEquals(0, count("SELECT COUNT(*) FROM sys_user u JOIN doctor d ON d.id=u.doctor_id WHERE u.role IN ('DOCTOR','PHARMACIST') AND u.role<>d.role_type"),
                "档案类型改变但原医生账号角色仍然保留");
    }

    @Test
    @DisplayName("契约F04：配药科室局部更新不能默认改成临床")
    void partialDepartmentUpdateKeepsPharmacyType() {
        Department update = new Department(); update.setId(2L); update.setName("Synthetic pharmacy edited");
        assertTrue(departments.update(update));
        String type = jdbc.queryForObject("SELECT type FROM department WHERE id=2", String.class);
        printEvidence("F04-department-type", type);
        assertEquals("PHARMACY", type, "更新请求未提供类型时应沿用原类型");
    }

    @Test
    @DisplayName("契约F04：临床医生调往配药科必须处理角色一致性")
    void clinicalStaffCannotMoveToPharmacyUnchanged() {
        Doctor edit = new Doctor(); edit.setId(1L); edit.setName("Synthetic moved");
        edit.setDepartmentId(2L); edit.setRoleType("DOCTOR"); edit.setStatus(1);
        try { doctors.update(edit); }
        catch (IllegalArgumentException expectedRejection) { /* 拒绝后仍验证持久化状态。 */ }
        assertEquals(0, count("SELECT COUNT(*) FROM doctor d JOIN department dep ON dep.id=d.department_id WHERE d.role_type='DOCTOR' AND dep.type='PHARMACY'"),
                "临床医生档案被放进配药科但类型未同步");
    }

    @Test
    @DisplayName("契约F05：单条排班写入必须与患者解析格式一致")
    void singleScheduleMustBePatientParseable() {
        Schedule schedule = schedule("09:00-10:00");
        boolean accepted = false;
        try { accepted = schedules.save(schedule); }
        catch (IllegalArgumentException expectedRejection) { /* 拒绝后仍检查没有非法落库。 */ }
        List<String> slots = jdbc.queryForList("SELECT time_slot FROM schedule", String.class);
        if (accepted) assertFalse(slots.isEmpty());
        assertTrue(slots.stream().allMatch(value -> BookingTime.normalizeTime(value).isPresent()),
                "后台保存了患者不能预约的时间区间");
    }

    @Test
    @DisplayName("契约F05：批量排班不能保存不存在的时间")
    void batchScheduleMustBePatientParseable() {
        ScheduleBatchRequest batch = new ScheduleBatchRequest();
        batch.setDoctorId(1L); batch.setDates(List.of(LocalDate.now().plusDays(1).toString()));
        batch.setTimeSlots(List.of("99:99")); batch.setTotalQuota(2); batch.setStatus(1);
        try { schedules.saveBatch(batch); }
        catch (IllegalArgumentException expectedRejection) { /* 拒绝后仍检查非法写入。 */ }
        List<String> slots = jdbc.queryForList("SELECT time_slot FROM schedule", String.class);
        assertTrue(slots.stream().allMatch(value -> BookingTime.normalizeTime(value).isPresent()),
                "批量路径保存了无法解析的时间");
    }

    @Test
    @DisplayName("契约F05：编辑排班同样要校验严格时间格式")
    void updatedScheduleMustBePatientParseable() {
        slot(100, 2, 0);
        Schedule schedule = schedule("9:00"); schedule.setId(100L);
        try { schedules.update(schedule); }
        catch (IllegalArgumentException expectedRejection) { /* 拒绝后仍检查数据库中的原排班。 */ }
        assertTrue(BookingTime.normalizeTime(jdbc.queryForObject("SELECT time_slot FROM schedule WHERE id=100", String.class)).isPresent(),
                "编辑路径保存了不符合患者格式的时间");
    }

    @Test
    @DisplayName("契约F10：在岗医生统计不得包含配药师")
    void dashboardDoctorCountExcludesPharmacists() {
        Number actual = dashboard.stats().getTotalDoctors();
        printEvidence("F10-dashboard", "clinical=1,pharmacist=1,reported=" + actual);
        assertEquals(1L, actual.longValue(), "医生统计把配药师也计入");
    }

    @Test
    @DisplayName("学习项目配置：新员工使用指定初始密码123登录")
    void newlyCreatedStaffAcceptsConfiguredInitialPassword() {
        Doctor doctor = new Doctor();
        doctor.setName("Synthetic New Employee"); doctor.setDepartmentId(1L); doctor.setStatus(1);
        doctors.createWithAccount(doctor);
        String username = jdbc.queryForObject("SELECT username FROM sys_user WHERE doctor_id=?", String.class, doctor.getId());
        Result response = login.login(username, "123");
        printEvidence("F09-shared-password", "loginCode=" + response.getCode());
        assertEquals(200, response.getCode(), "新员工应可使用指定的初始密码123登录");
    }

    @Test
    @DisplayName("契约F09：连续错误登录后应有失败次数或频率限制")
    void repeatedFailedLoginHasEnforcement() {
        for (int i = 0; i < 12; i++) assertNotEquals(200, login.login("synthetic_10", "Wrong-Synthetic-Password").getCode());
        Result afterFailures = login.login("synthetic_10", "Synthetic-Test-Only-Password");
        printEvidence("F09-login-attempts", "failedAttempts=12,nextCorrectPasswordCode=" + afterFailures.getCode());
        assertNotEquals(200, afterFailures.getCode(), "连续12次错误尝试后没有任何锁定或限流，此处仅测试合成账号");
    }

    @Test
    @DisplayName("契约F03：生产SQL日志模式不得输出用户密码散列")
    void productionSqlLoggingDoesNotExposePasswordHashes() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        Properties actualConfiguration = new Properties();
        try (var stream = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(stream);
            actualConfiguration.load(stream);
        }
        String logImplementation = actualConfiguration.getProperty("mybatis-plus.configuration.log-impl",
                NoLoggingImpl.class.getName());
        configuration.setLogImpl(Class.forName(logImplementation).asSubclass(Log.class));
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(sink);
            try (var session = factory.getObject().openSession()) {
                session.getMapper(LoginDao.class).getById(10L);
            }
        } finally { System.setOut(original); }
        boolean exposed = captured.toString(StandardCharsets.UTF_8).contains(SYNTHETIC_PASSWORD_HASH);
        printEvidence("F03-sql-log", "syntheticPasswordHashVisible=" + exposed + ";原始散列不写入证据");
        assertFalse(exposed, "项目当前默认SQL日志配置和真实用户Mapper会把合成账号密码散列写入日志");
    }

    @Test
    @DisplayName("诊断F11：映射失效会拒绝绑定，记录文件是否保留但不假定清理时限")
    void expiredUnboundAvatarRetentionDiagnostic() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        // 显式模拟映射已过期；此用例不宣称验证了真实 Redis TTL。
        when(values.get(anyString())).thenReturn(null);
        MiniAvatarServiceImpl avatars = new MiniAvatarServiceImpl(redis, miniAuthDao, login, avatarWriter);
        ReflectionTestUtils.setField(avatars, "avatarDirectory", avatarDirectory.toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        assertEquals(200, avatars.upload(10L, new MockMultipartFile("file", "synthetic.png", "image/png", bytes.toByteArray()), "avatar").getCode());
        Path saved;
        try (var stream = Files.list(avatarDirectory)) { saved = stream.findFirst().orElseThrow(); }
        MiniAvatarBindRequest bind = new MiniAvatarBindRequest(); bind.setPath("/api/avatar/" + saved.getFileName());
        assertNotEquals(200, avatars.bind(10L, bind).getCode());
        printEvidence("F11-retention-diagnostic", "expiredMappingRejectsBind=true,fileRetained=" + Files.exists(saved)
                + ";没有约定清理时限，不能以必须立即删除作为修复门槛");
    }

    private Schedule schedule(String timeSlot) {
        Schedule schedule = new Schedule();
        schedule.setDoctorId(1L); schedule.setScheduleDate(LocalDate.now().plusDays(1));
        schedule.setTimeSlot(timeSlot); schedule.setTotalQuota(2); schedule.setStatus(1);
        return schedule;
    }
}
