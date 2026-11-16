package com.aaa.aihealthcare.payment;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** 仅在本副本显式启用 Native 时增量建表，不导入原项目数据。 */
@Component
@ConditionalOnProperty(name="healthcare.payment.wechat-native.enabled", havingValue="true")
public class NativePaymentSchemaInitializer implements InitializingBean {
    private final DataSource source;
    public NativePaymentSchemaInitializer(DataSource source) { this.source = source; }
    @Override public void afterPropertiesSet() {
        String schema = new JdbcTemplate(source).queryForObject("SELECT DATABASE()", String.class);
        if (!"aihealthcare_local".equals(schema)
                && (schema == null || !schema.matches("aihealthcare_verify_[0-9]{8}_[a-f0-9]{8}"))) {
            throw new IllegalStateException("Native支付表只允许在当前副本或专属验证库中初始化");
        }
        new ResourceDatabasePopulator(new ClassPathResource("native-payment-schema.sql")).execute(source);
    }
}
