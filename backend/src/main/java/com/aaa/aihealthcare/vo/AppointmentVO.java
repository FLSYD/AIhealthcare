package com.aaa.aihealthcare.vo;

import com.aaa.aihealthcare.entity.Appointment;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 预约列表/详情出参：预约本体 + 科室、医生、排班、处方等关联信息。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AppointmentVO extends Appointment {
    /** 就诊日期 */
    private LocalDate scheduleDate;
    /** 就诊时段 */
    private String timeSlot;
    /** 医生姓名 */
    private String doctorName;
    /** 科室名称 */
    private String departmentName;
    /** 处方 id，无处方时为空 */
    private Long prescriptionId;
    /** 处方状态，无处方时为空 */
    private String prescriptionStatus;
    /** 处方总金额 */
    private BigDecimal prescriptionAmount;
    /** 排班状态：0 停诊 / 1 开放 */
    private Integer scheduleStatus;
}
