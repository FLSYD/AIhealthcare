package com.aaa.aihealthcare.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 患者端可预约排班。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatientScheduleVO {
    private Long id;
    private Long doctorId;
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDate scheduleDate;
    private String timeSlot;
    private Integer totalQuota;
    private Integer bookedCount;
    private Integer remainQuota;
    private Integer status;
}
