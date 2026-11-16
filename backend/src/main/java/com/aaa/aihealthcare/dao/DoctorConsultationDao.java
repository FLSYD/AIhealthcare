package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.ConsultationRecord;
import com.aaa.aihealthcare.entity.Medicine;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.entity.PrescriptionItem;
import com.aaa.aihealthcare.vo.AppointmentVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 医生诊疗操作的归属校验、行锁与条件写 SQL。
 */
public interface DoctorConsultationDao {

    AppointmentVO selectAppointmentForUpdate(@Param("doctorId") Long doctorId,
                                             @Param("appointmentId") Long appointmentId);

    /** 配药师发药：不限定开方医生。 */
    AppointmentVO selectAppointmentForUpdateByPharmacy(@Param("appointmentId") Long appointmentId);

    ConsultationRecord selectRecord(@Param("doctorId") Long doctorId,
                                    @Param("appointmentId") Long appointmentId);

    ConsultationRecord selectRecordForUpdate(@Param("doctorId") Long doctorId,
                                             @Param("appointmentId") Long appointmentId);

    List<Prescription> selectPrescriptions(@Param("appointmentId") Long appointmentId);

    List<Prescription> selectPrescriptionsForUpdate(@Param("appointmentId") Long appointmentId);

    List<PrescriptionItem> selectItems(@Param("prescriptionId") Long prescriptionId);

    List<PrescriptionItem> selectItemsForUpdate(@Param("prescriptionId") Long prescriptionId);

    List<Medicine> selectMedicinesForUpdate(@Param("medicineIds") List<Long> medicineIds);

    int insertRecord(ConsultationRecord record);

    int updateRecord(ConsultationRecord record);

    int insertItem(PrescriptionItem item);

    int updateAppointmentStatus(@Param("doctorId") Long doctorId,
                                @Param("appointmentId") Long appointmentId,
                                @Param("expectedStatuses") List<String> expectedStatuses,
                                @Param("newStatus") String newStatus);

    int deductMedicineStock(@Param("medicineId") Long medicineId,
                            @Param("quantity") Integer quantity);

    int updatePrescriptionToDispensed(@Param("prescriptionId") Long prescriptionId,
                                      @Param("appointmentId") Long appointmentId,
                                      @Param("userId") Long userId,
                                      @Param("doctorId") Long doctorId);

    /** 配药师发药：不按开方医生过滤。 */
    int updatePrescriptionToDispensedForPharmacy(@Param("prescriptionId") Long prescriptionId,
                                                 @Param("appointmentId") Long appointmentId,
                                                 @Param("userId") Long userId);

    /** 配药师发药：不限定排班所属医生。 */
    int updateAppointmentStatusForPharmacy(@Param("appointmentId") Long appointmentId,
                                           @Param("expectedStatuses") List<String> expectedStatuses,
                                           @Param("newStatus") String newStatus);
}
