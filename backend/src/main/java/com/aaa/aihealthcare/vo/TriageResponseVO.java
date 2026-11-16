package com.aaa.aihealthcare.vo;

import lombok.Data;

import java.util.List;

@Data
public class TriageResponseVO {
    private String summary;
    private String symptomAnalysis;
    private List<String> commonSigns;
    private String preliminaryConclusion;
    private String advice;
    private boolean urgent;
    private String source;
    private List<TriageDepartmentVO> departments;
}
