package com.aaa.aihealthcare.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 配药工作台使用的处方视图，含患者与开方医生信息。 */
@Data
public class PrescriptionVO {
    private Long id;
    private Long appointmentId;
    private Long doctorId;
    private Long userId;

    /** 患者姓名 */
    private String patientName;
    /** 患者手机号 */
    private String patientPhone;
    /** 开方医生姓名 */
    private String doctorName;
    /** 科室名称 */
    private String departmentName;

    private BigDecimal totalAmount;
    /** unpaid / paid / dispensed / cancelled */
    private String status;
    private String statusText;

    private String payMethod;
    private LocalDateTime payTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 药品明细 */
    private List<PrescriptionItemView> items;

    /** 处方明细行 */
    @Data
    public static class PrescriptionItemView {
        private Long medicineId;
        private String medicineName;
        private String spec;
        /** 数量 */
        private Integer quantity;
        /** 单价 */
        private BigDecimal unitPrice;
        /** 小计 */
        private BigDecimal amount;
        /** 用法用量 */
        private String dosage;
        /** 用药频次 */
        private String frequency;
        /** 用药天数 */
        private Integer days;
    }
}
