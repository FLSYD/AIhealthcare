package com.aaa.aihealthcare.payment;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Native 支付交易；不直接作为接口响应序列化。 */
@Getter
@Setter
public class NativePayment {
    private Long id;
    private String businessType;
    private Long businessId;
    private Long userId;
    private Long amountFen;
    private String merchantOrderNo;
    private String transactionId;
    private String requestHash;
    private String activeKey;
    private String state;
    private String codeUrl;
    private String prepayId;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime expiresAt;
    private LocalDateTime lastQueryAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
