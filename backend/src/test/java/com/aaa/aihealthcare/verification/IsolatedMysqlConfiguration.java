package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.service.impl.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 仅由显式启用的集成测试加载，不扫描应用配置，也不修改业务连接隔离守卫。
 * 所有真实 SQL 使用专属账号，且必须命中本次创建的库及标识。
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@MapperScan("com.aaa.aihealthcare.dao")
@Import({PatientCommandServiceImpl.class, AppointmentServiceImpl.class,
        DoctorConsultationServiceImpl.class, NotificationServiceImpl.class,
        ScheduleServiceImpl.class, SysUserServiceImpl.class, DepartmentServiceImpl.class,
        DoctorServiceImpl.class, DashboardServiceImpl.class, LoginServiceImpl.class,
        SysLoginLogServiceImpl.class, MedicineServiceImpl.class, AvatarWriteExecutor.class})
public class IsolatedMysqlConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource() throws Exception {
        if (!"true".equals(System.getProperty("healthcare.verify.enabled"))) {
            throw new IllegalStateException("真实数据库验证必须显式启用。");
        }
        Path config = Path.of(System.getProperty("healthcare.verify.config", "")).toAbsolutePath().normalize();
        Properties settings = new Properties();
        try (InputStream stream = Files.newInputStream(config)) {
            settings.load(stream);
        }
        String url = settings.getProperty("jdbc.url", "");
        String user = settings.getProperty("jdbc.username", "");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3306/aihealthcare_verify_[0-9]{8}_[a-f0-9]{8}\\?characterEncoding=UTF-8&serverTimezone=Asia/Hong_Kong")
                || !user.matches("ahv_[a-f0-9]{8}")) {
            throw new IllegalStateException("拒绝使用非专属验证库或账号。");
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(settings.getProperty("jdbc.password"));
        hikari.setMaximumPoolSize(16);
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(10000);
        hikari.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=8");
        hikari.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        hikari.setPoolName("IsolatedMysqlVerification");
        HikariDataSource source = new HikariDataSource(hikari);
        try (var connection = source.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT run_marker FROM _verification_guard WHERE id=1")) {
            if (!result.next() || !settings.getProperty("run.marker", "").equals(result.getString(1))) {
                throw new IllegalStateException("测试库标识不一致。");
            }
        } catch (Exception exception) {
            source.close();
            throw exception;
        }
        return source;
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(NoLoggingImpl.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(new com.github.pagehelper.PageInterceptor());
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        return factory.getObject();
    }

    @Bean
    DataSourceTransactionManager transactionManager(DataSource source) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        manager.setDefaultTimeout(20);
        return manager;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource source) {
        return new JdbcTemplate(source);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    StringRedisTemplate stringRedisTemplate() {
        // 此套件只验证 MySQL 事务；会话存储使用隔离内存替身，不接入运行中的 Redis。
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> memory = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(invocation -> memory.get(invocation.<String>getArgument(0)));
        doAnswer(invocation -> { memory.put(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString());
        doAnswer(invocation -> { memory.put(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        when(values.setIfAbsent(anyString(), anyString())).thenAnswer(invocation ->
                memory.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
        when(redis.delete(anyString())).thenAnswer(invocation -> memory.remove(invocation.<String>getArgument(0)) != null);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    String count = memory.compute(keys.getFirst(), (key, old) -> Long.toString(old == null ? 1 : Long.parseLong(old) + 1));
                    return Long.valueOf(count);
                });
        return redis;
    }
}
