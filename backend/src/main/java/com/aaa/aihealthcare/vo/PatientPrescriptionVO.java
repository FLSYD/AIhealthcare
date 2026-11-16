package com.aaa.aihealthcare.vo;

import com.aaa.aihealthcare.entity.PrescriptionItem;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 患者本人处方及明细。
 */
@Data
public class PatientPrescriptionVO {
    private Long id;
    private Long appointmentId;
    private Long doctorId;
    private BigDecimal totalAmount;
    private String status;
    private String statusText;
    private String payMethod;
    private String payMethodText;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime payTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
    private List<PrescriptionItem> items;
}
