package com.aaa.aihealthcare.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 独立整改副本的连接边界：在创建数据库和 Redis 客户端前拒绝其他数据服务。 */
@Configuration(proxyBeanMethods = false)
public class LocalDataIsolationConfiguration {
    static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/aihealthcare_local?characterEncoding=UTF-8&serverTimezone=Asia/Hong_Kong";

    @Bean
    static BeanFactoryPostProcessor enforceLocalDataIsolation(Environment environment) {
        return beanFactory -> validate(environment);
    }

    static void validate(Environment environment) {
        require(JDBC_URL.equals(environment.getProperty("spring.datasource.url")), "spring.datasource.url");
        require("aihealthcare_app".equals(environment.getProperty("spring.datasource.username")), "spring.datasource.username");
        require("127.0.0.1".equals(environment.getProperty("spring.data.redis.host")), "spring.data.redis.host");
        require("16379".equals(environment.getProperty("spring.data.redis.port")), "spring.data.redis.port");
        require("0".equals(environment.getProperty("spring.data.redis.database")), "spring.data.redis.database");
        for (String key : new String[] {"spring.datasource.jndi-name", "spring.data.redis.url",
                "spring.data.redis.cluster.nodes", "spring.data.redis.sentinel.master", "spring.data.redis.sentinel.nodes"}) {
            require(!hasText(environment.getProperty(key)), key);
        }
        String hikariUrl = environment.getProperty("spring.datasource.hikari.jdbc-url");
        require(!hasText(hikariUrl) || JDBC_URL.equals(hikariUrl), "spring.datasource.hikari.jdbc-url");
        String hikariUser = environment.getProperty("spring.datasource.hikari.username");
        require(!hasText(hikariUser) || "aihealthcare_app".equals(hikariUser), "spring.datasource.hikari.username");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean allowed, String property) {
        if (!allowed) {
            throw new IllegalStateException("独立副本拒绝非专属本地数据配置，请检查 " + property);
        }
    }
}
