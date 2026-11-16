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
 * 预约记录，对应 appointment 表。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("appointment")
public class Appointment {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 预约用户（患者） */
    @TableField("user_id")
    private Long userId;
    /** 关联排班号源 */
    @TableField("schedule_id")
    private Long scheduleId;
    /** 就诊人姓名 */
    @TableField("patient_name")
    private String patientName;
    /** 就诊人手机号 */
    @TableField("patient_phone")
    private String patientPhone;
    /** 身份证号，可空 */
    @TableField("id_card")
    private String idCard;
    /** 挂号费 */
    private BigDecimal amount;
    /** balance / wechat / alipay */
    @TableField("pay_method")
    private String payMethod;
    @TableField("pay_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime payTime;
    /** 预约状态，见 AppointmentStatus */
    private String status;
    private String remark;
    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    @TableField("update_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}
