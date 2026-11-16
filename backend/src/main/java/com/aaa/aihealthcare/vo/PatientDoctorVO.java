package com.aaa.aihealthcare.vo;

import com.aaa.aihealthcare.entity.Doctor;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 患者端医生公开档案。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PatientDoctorVO extends Doctor {
    private String departmentName;
    private BigDecimal rating;
    private Long reviewCount;
    private Long consultCount;
    private Boolean hasAvailableSlot;
}
