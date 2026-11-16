package com.aaa.aihealthcare.vo;

import java.math.BigDecimal;

public record RechargeRequest(BigDecimal amount, String payMethod) {
}
