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
@TableName("department")
public class Department {
    /** 科室类型：临床科室，患者端可见可挂号。 */
    public static final String TYPE_CLINICAL = "CLINICAL";
    /** 科室类型：配药科，患者端不可见，该科室人员创建为配药师账号。 */
    public static final String TYPE_PHARMACY = "PHARMACY";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    /** CLINICAL / PHARMACY，默认 CLINICAL */
    private String type;
    private String description;
    private String location;
    @TableField("sort_order")
    private Integer sortOrder;
    private Integer status;
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /** 是否为配药科。 */
    public boolean isPharmacy() {
        return TYPE_PHARMACY.equals(type);
    }
}
