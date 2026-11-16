package com.aaa.aihealthcare.vo;

import lombok.Data;

import java.util.List;

@Data
public class TriageDepartmentVO {
    private Long id;
    private String name;
    private String description;
    private Integer score;
    private List<String> symptoms;
}
