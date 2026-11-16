package com.aaa.aihealthcare.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用「名称-数值」结构，供图表数据（趋势、分布、排行）使用。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class NameValueVO {
    private String name;
    private Long value;
}
