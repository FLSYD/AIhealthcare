package com.aaa.aihealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 诊疗记录，对应 consultation_record 表。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("consultation_record")
public class ConsultationRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("appointment_id")
    private Long appointmentId;
    @TableField("doctor_id")
    private Long doctorId;
    @TableField("chief_complaint")
    private String chiefComplaint;
    @TableField("present_illness")
    private String presentIllness;
    @TableField("diagnosis")
    private String diagnosis;
    @TableField("treatment_plan")
    private String treatmentPlan;
    @TableField("doctor_advice")
    private String medicalAdvice;
    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    @TableField("update_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}
