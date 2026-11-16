package com.aaa.aihealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 处方，对应 prescription 表。一次预约最多对应一张处方。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("prescription")
public class Prescription {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("appointment_id")
    private Long appointmentId;
    @TableField("user_id")
    private Long userId;
    @TableField("doctor_id")
    private Long doctorId;
    @TableField("total_amount")
    private BigDecimal totalAmount;
    /** unpaid / paid / dispensed / cancelled */
    private String status;
    @TableField("pay_method")
    private String payMethod;
    @TableField("pay_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime payTime;
    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    @TableField("update_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}
