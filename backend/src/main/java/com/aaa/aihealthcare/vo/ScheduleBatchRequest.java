package com.aaa.aihealthcare.vo;

import lombok.Data;

import java.util.List;

/** 批量新增排班请求：多个日期 × 多个时段一次生成。 */
@Data
public class ScheduleBatchRequest {
    private Long doctorId;
    /** 日期列表，格式 yyyy-MM-dd，最多 5 天 */
    private List<String> dates;
    /** 时段列表，如 08:00、14:00，最多 6 个 */
    private List<String> timeSlots;
    /** 每个时段的总号源 */
    private Integer totalQuota;
    /** 状态 1=开放 0=停诊，默认 1 */
    private Integer status;
}
