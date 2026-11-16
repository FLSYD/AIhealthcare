package com.aaa.aihealthcare.payment;

import java.time.Instant;

/** Native 微信支付冻结接口。 */
public interface NativeWechatGateway {
    record Order(String outTradeNo, long totalFee, String description, Instant expiresAt) {
    }

    record Prepay(String codeUrl, String prepayId) {
    }

    record Trade(String outTradeNo, String transactionId, Long totalFee, String tradeState,
                 Instant successTime, String payerOpenid) {
    }

    record Closed(String state) {
    }

    Prepay create(Order order);

    Trade query(String outTradeNo);

    Closed close(String outTradeNo);

    Trade parseNotification(String xml);

    /** 网关异常只携带稳定错误码与结果确定性，不携带敏感请求、响应或凭据。 */
    class GatewayException extends RuntimeException {
        private final String code;
        private final boolean uncertain;

        public GatewayException(String code, String message, boolean uncertain) {
            super(message);
            this.code = code;
            this.uncertain = uncertain;
        }

        public String getCode() {
            return code;
        }

        public boolean isUncertain() {
            return uncertain;
        }
    }
}