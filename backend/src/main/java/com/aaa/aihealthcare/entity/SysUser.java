package com.aaa.aihealthcare.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("sys_user")
public class SysUser {
    @TableField(exist = false)
    private String keyword;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String nickname;
    private String email;
    private String phone;
    private String avatar;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String openid;
    private BigDecimal balance;
    @TableField("doctor_id")
    private Long doctorId;
    private Integer status;
    private String role;
    private Integer deleted;
    @TableField(exist = false)
    private Date createTime;
    @TableField(exist = false)
    private Date updateTime;
}
    
