package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.entity.PrescriptionItem;
import com.aaa.aihealthcare.vo.PatientAppointmentVO;
import com.aaa.aihealthcare.vo.PatientDoctorVO;
import com.aaa.aihealthcare.vo.PatientRecordVO;
import com.aaa.aihealthcare.vo.PatientScheduleVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 患者公开目录与本人历史数据的只读查询。
 */
public interface PatientQueryDao {
    List<Department> selectActiveDepartments();

    List<PatientDoctorVO> selectActiveDoctors(@Param("keyword") String keyword,
                                              @Param("departmentId") Long departmentId);

    PatientDoctorVO selectActiveDoctorById(@Param("id") Long id);

    List<PatientScheduleVO> selectPatientSchedules(@Param("doctorId") Long doctorId,
                                                   @Param("scheduleDate") LocalDate scheduleDate);

    List<PatientScheduleVO> selectBookableScheduleWindows(@Param("startDate") LocalDate startDate,
                                                          @Param("endDate") LocalDate endDate,
                                                          @Param("doctorId") Long doctorId);

    List<PatientAppointmentVO> selectPatientAppointments(@Param("userId") Long userId,
                                                         @Param("status") String status);

    PatientAppointmentVO selectPatientAppointmentDetail(@Param("userId") Long userId,
                                                        @Param("id") Long id);

    Long selectOwnedAppointmentId(@Param("userId") Long userId, @Param("id") Long id);

    PatientRecordVO selectPatientRecord(@Param("userId") Long userId,
                                        @Param("appointmentId") Long appointmentId);

    Prescription selectPrescriptionByAppointment(@Param("appointmentId") Long appointmentId);

    Prescription selectPrescriptionByUser(@Param("userId") Long userId,
                                          @Param("id") Long id);

    List<PrescriptionItem> selectPrescriptionItems(@Param("prescriptionId") Long prescriptionId);
}
