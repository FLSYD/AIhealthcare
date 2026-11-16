package com.aaa.aihealthcare.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 患者本人预约列表与详情。
 */
@Data
public class PatientAppointmentVO {
    private Long id;
    private Long scheduleId;
    private String patientName;
    private String patientPhone;
    private String idCard;
    private BigDecimal amount;
    private String payMethod;
    private String payMethodText;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime payTime;
    private String status;
    private String statusText;
    private String remark;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
    private LocalDate scheduleDate;
    private String timeSlot;
    private Long doctorId;
    private String doctorName;
    private String doctorTitle;
    private Long departmentId;
    private String departmentName;
    private Long prescriptionId;
    private String prescriptionStatus;
    private BigDecimal prescriptionAmount;
    private Boolean hasConsultation;
    private Boolean evaluated;
}
