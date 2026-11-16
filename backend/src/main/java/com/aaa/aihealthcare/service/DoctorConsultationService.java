package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.ConsultationRecord;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.vo.ConsultationDetailVO;
import com.aaa.aihealthcare.vo.ConsultationRecordRequest;
import com.aaa.aihealthcare.vo.PrescriptionRequest;

/**
 * 医生本人预约的诊疗操作。
 */
public interface DoctorConsultationService {

    ConsultationDetailVO getDetail(SysUser currentUser, Long appointmentId);

    void start(SysUser currentUser, Long appointmentId);

    ConsultationRecord saveRecord(SysUser currentUser, Long appointmentId,
                                  ConsultationRecordRequest request);

    Prescription prescribe(SysUser currentUser, Long appointmentId,
                           PrescriptionRequest request);

    void complete(SysUser currentUser, Long appointmentId);

    void dispense(SysUser currentUser, Long appointmentId);

    void dispenseForAdmin(Long prescriptionId);

    /** 配药师发药：仅接受已付款处方，不限定开方医生。 */
    void dispenseAsPharmacist(Long prescriptionId);
}
