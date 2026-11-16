package com.aaa.aihealthcare.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Native 微信支付配置；刻意不生成 toString，避免密钥进入日志。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "healthcare.payment.wechat-native")
public class NativeWechatProperties {
    private boolean enabled = false;
    private String appid;
    private String mchId;
    private String apiKey;
    private String notifyUrl = "http://127.0.0.1:18080/api/payments/wechat/notify";
    private String cashierBaseUrl = "http://127.0.0.1:15173";
    private String cashierSigningKey;
    private int requestTimeoutSeconds = 10;
}