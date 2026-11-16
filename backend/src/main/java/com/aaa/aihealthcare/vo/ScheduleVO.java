package com.aaa.aihealthcare.vo;

import com.aaa.aihealthcare.entity.Schedule;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ScheduleVO extends Schedule {
    private String doctorName;
    private String title;
    private String departmentName;
    /** 全部历史预约数，包含已取消与已完成记录，用于解释删除保护。 */
    private Long appointmentCount;
}
