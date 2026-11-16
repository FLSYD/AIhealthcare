package com.aaa.aihealthcare.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;

/** 当前登录用户的消息，不返回接收人身份字段。 */
@Data
public class NotificationVO {
    private Long id;
    private String type;
    private String title;
    private String content;
    private String bizType;
    private Long bizId;
    private boolean read;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    public String getTypeText() {
        if (type == null) return "消息通知";
        return switch (type) {
            case "schedule_changed" -> "排班变更";
            case "appointment_created" -> "预约提醒";
            case "appointment_paid" -> "挂号支付";
            case "appointment_checked_in" -> "到院签到";
            case "appointment_cancelled" -> "预约取消";
            case "consultation_started" -> "开始就诊";
            case "prescription_created" -> "处方通知";
            case "prescription_paid" -> "药费支付";
            case "prescription_dispensed" -> "取药完成";
            case "appointment_completed" -> "就诊完成";
            default -> "消息通知";
        };
    }
}
