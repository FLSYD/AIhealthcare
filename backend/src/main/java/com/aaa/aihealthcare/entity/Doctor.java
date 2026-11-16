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

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("doctor")
public class Doctor {
    /** 人员类型：医生，可接诊与排班，患者端可见。 */
    public static final String ROLE_DOCTOR = "DOCTOR";
    /** 人员类型：配药师，只在配药工作台发药，不对外展示。 */
    public static final String ROLE_PHARMACIST = "PHARMACIST";

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("department_id")
    private Long departmentId;
    private String name;
    /** DOCTOR / PHARMACIST，默认 DOCTOR */
    @TableField("role_type")
    private String roleType;
    private String title;
    private Integer gender;
    private String avatar;
    private String intro;
    private Integer status;
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /** 是否为配药师。 */
    public boolean isPharmacist() {
        return ROLE_PHARMACIST.equals(roleType);
    }
}