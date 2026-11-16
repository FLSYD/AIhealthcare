package com.aaa.aihealthcare.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WalletLogVO {
    private Long id;
    private String type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private Long orderId;
    private String payMethod;
    private String remark;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    public String getTypeText() {
        if (type == null) return "账户记录";
        return switch (type) {
            case "recharge" -> "余额充值";
            case "order_pay" -> "余额挂号付款";
            case "prescription_pay" -> "余额药费付款";
            case "order_refund" -> "挂号退款";
            case "mock_order_pay" -> "模拟挂号付款（不扣余额）";
            case "mock_rx_pay" -> "模拟药费付款（不扣余额）";
            case "mock_order_refund" -> "模拟挂号退款（不加余额）";
            default -> "账户记录";
        };
    }
}
