package com.aaa.aihealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 处方明细，对应 prescription_item 表。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("prescription_item")
public class PrescriptionItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("prescription_id")
    private Long prescriptionId;
    @TableField("medicine_id")
    private Long medicineId;
    @TableField("medicine_name")
    private String medicineName;
    private String spec;
    private String dosage;
    private String frequency;
    private Integer days;
    private Integer quantity;
    @TableField("unit_price")
    private BigDecimal unitPrice;
    private BigDecimal amount;
}
