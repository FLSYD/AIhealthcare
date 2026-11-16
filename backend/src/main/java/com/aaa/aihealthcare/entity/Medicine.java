package com.aaa.aihealthcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 药品实体，对应 medicine 表。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("medicine")
public class Medicine {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 药品名称 */
    private String name;
    /** 规格，如 0.25g*24片 */
    private String spec;
    /** 单位，默认“盒” */
    private String unit;
    /** 单价 */
    private BigDecimal price;
    /** 库存 */
    private Integer stock;
    /** 更新库存时客户端看到的原库存，仅用于请求，不写入数据库。 */
    @TableField(exist = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Integer expectedStock;
    /** 用法说明 */
    @TableField("usage_desc")
    private String usageDesc;
    /** 1 启用 / 0 停用 */
    private Integer status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;
}
