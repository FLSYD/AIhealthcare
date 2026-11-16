package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.vo.MiniPageVO;
import com.aaa.aihealthcare.vo.PatientAppointmentVO;
import com.aaa.aihealthcare.vo.PatientDoctorVO;
import com.aaa.aihealthcare.vo.PatientPrescriptionVO;
import com.aaa.aihealthcare.vo.PatientRecordVO;
import com.aaa.aihealthcare.vo.PatientScheduleVO;

import java.util.List;

public interface PatientQueryService {
    List<Department> listDepartments();

    List<PatientDoctorVO> listDoctors(Long departmentId, String keyword, Boolean hasAvailableSlot);

    PatientDoctorVO getDoctor(Long id);

    List<PatientScheduleVO> listSchedules(Long doctorId, String scheduleDate);

    MiniPageVO<PatientAppointmentVO> pageAppointments(Long userId, String status,
                                                       Integer current, Integer size);

    PatientAppointmentVO getAppointment(Long userId, Long id);

    PatientRecordVO getConsultation(Long userId, Long appointmentId);

    PatientPrescriptionVO getPrescriptionByAppointment(Long userId, Long appointmentId);

    PatientPrescriptionVO getPrescription(Long userId, Long prescriptionId);
}
