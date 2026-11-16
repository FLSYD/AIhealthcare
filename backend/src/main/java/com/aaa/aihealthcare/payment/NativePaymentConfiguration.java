package com.aaa.aihealthcare.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NativeWechatProperties.class)
public class NativePaymentConfiguration {
    @Bean
    @ConditionalOnProperty(name="healthcare.payment.wechat-native.enabled", havingValue="true")
    NativeWechatGateway nativeWechatGateway(NativeWechatProperties properties) {
        return new NativeWechatClient(properties);
    }
}
