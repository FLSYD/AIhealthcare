package com.aaa.aihealthcare.vo;

import lombok.Data;

/**
 * 医生保存病历的请求体，不包含预约、医生或时间等服务端字段。
 */
@Data
public class ConsultationRecordRequest {
    private String chiefComplaint;
    private String presentIllness;
    private String diagnosis;
    private String treatmentPlan;
    private String medicalAdvice;
}
