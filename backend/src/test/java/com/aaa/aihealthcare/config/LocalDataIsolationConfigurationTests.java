package com.aaa.aihealthcare.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalDataIsolationConfigurationTests {
    private MockEnvironment local() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", LocalDataIsolationConfiguration.JDBC_URL)
                .withProperty("spring.datasource.username", "aihealthcare_app")
                .withProperty("spring.data.redis.host", "127.0.0.1")
                .withProperty("spring.data.redis.port", "16379")
                .withProperty("spring.data.redis.database", "0");
    }

    @Test
    void acceptsDedicatedLocalServices() {
        assertDoesNotThrow(() -> LocalDataIsolationConfiguration.validate(local()));
    }

    @Test
    void rejectsRemoteDatabaseAndRootAccount() {
        assertThrows(IllegalStateException.class, () -> LocalDataIsolationConfiguration.validate(
                local().withProperty("spring.datasource.url", "jdbc:mysql://192.168.0.59:3306/hospital_booking")));
        assertThrows(IllegalStateException.class, () -> LocalDataIsolationConfiguration.validate(
                local().withProperty("spring.datasource.username", "root")));
    }

    @Test
    void rejectsSharedRedisEvenWhenItIsLocal() {
        assertThrows(IllegalStateException.class, () -> LocalDataIsolationConfiguration.validate(
                local().withProperty("spring.data.redis.port", "6379")));
        assertThrows(IllegalStateException.class, () -> LocalDataIsolationConfiguration.validate(
                local().withProperty("spring.data.redis.host", "192.168.0.59")));
    }

    @Test
    void rejectsAlternateClientConfigurationThatWouldBypassHostChecks() {
        for (String key : new String[] {"spring.datasource.jndi-name", "spring.datasource.hikari.jdbc-url",
                "spring.datasource.hikari.username", "spring.data.redis.url", "spring.data.redis.cluster.nodes",
                "spring.data.redis.sentinel.master", "spring.data.redis.sentinel.nodes"}) {
            assertThrows(IllegalStateException.class, () -> LocalDataIsolationConfiguration.validate(
                    local().withProperty(key, "unexpected-target")), key);
        }
    }
}
