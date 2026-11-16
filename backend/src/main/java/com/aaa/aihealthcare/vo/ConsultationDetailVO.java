package com.aaa.aihealthcare.vo;

import com.aaa.aihealthcare.entity.ConsultationRecord;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.entity.PrescriptionItem;
import lombok.Data;

import java.util.List;

/**
 * 医生诊疗详情页数据。
 */
@Data
public class ConsultationDetailVO {
    private AppointmentVO appointment;
    private ConsultationRecord record;
    private Prescription prescription;
    private List<PrescriptionItem> items;
    private List<String> allowedActions;
}
