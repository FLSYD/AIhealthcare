package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AppointmentStatus;
import com.aaa.aihealthcare.common.BookingTime;
import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.dao.PatientQueryDao;
import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.service.PatientQueryService;
import com.aaa.aihealthcare.vo.MiniPageVO;
import com.aaa.aihealthcare.vo.PatientAppointmentVO;
import com.aaa.aihealthcare.vo.PatientDoctorVO;
import com.aaa.aihealthcare.vo.PatientPrescriptionVO;
import com.aaa.aihealthcare.vo.PatientRecordVO;
import com.aaa.aihealthcare.vo.PatientScheduleVO;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PatientQueryServiceImpl implements PatientQueryService {
    private static final int BOOKING_DAYS = 7;

    @Autowired
    private PatientQueryDao patientQueryDao;

    @Override
    public List<Department> listDepartments() {
        return patientQueryDao.selectActiveDepartments();
    }

    @Override
    public List<PatientDoctorVO> listDoctors(Long departmentId, String keyword,
                                              Boolean hasAvailableSlot) {
        List<PatientDoctorVO> doctors = patientQueryDao.selectActiveDoctors(keyword, departmentId);
        Set<Long> availableDoctorIds = availableDoctorIds(null);
        doctors.forEach(doctor -> doctor.setHasAvailableSlot(
                availableDoctorIds.contains(doctor.getId())));
        if (Boolean.TRUE.equals(hasAvailableSlot)) {
            return doctors.stream()
                    .filter(doctor -> Boolean.TRUE.equals(doctor.getHasAvailableSlot()))
                    .toList();
        }
        return doctors;
    }

    @Override
    public PatientDoctorVO getDoctor(Long id) {
        PatientDoctorVO doctor = patientQueryDao.selectActiveDoctorById(id);
        if (doctor == null) {
            throw new IllegalArgumentException("医生不存在或已停用");
        }
        doctor.setHasAvailableSlot(availableDoctorIds(id).contains(id));
        return doctor;
    }

    @Override
    public List<PatientScheduleVO> listSchedules(Long doctorId, String scheduleDate) {
        if (doctorId == null) {
            throw new IllegalArgumentException("医生不能为空");
        }
        LocalDate date = BookingTime.parseScheduleDate(scheduleDate);
        BookingTime.validateScheduleDate(date);
        if (patientQueryDao.selectActiveDoctorById(doctorId) == null) {
            throw new IllegalArgumentException("医生不存在或已停用");
        }
        return patientQueryDao.selectPatientSchedules(doctorId, date).stream()
                .filter(schedule -> BookingTime.isBookable(schedule.getScheduleDate(),
                        schedule.getTimeSlot()))
                .peek(schedule -> schedule.setTimeSlot(
                        BookingTime.normalizeTime(schedule.getTimeSlot()).orElseThrow().toString()))
                .toList();
    }

    @Override
    public MiniPageVO<PatientAppointmentVO> pageAppointments(Long userId, String status,
                                                              Integer current, Integer size) {
        String normalizedStatus = normalizeAppointmentFilter(status);
        try {
            PageUtils.startPage(current, size);
            List<PatientAppointmentVO> records =
                    patientQueryDao.selectPatientAppointments(userId, normalizedStatus);
            records.forEach(this::enrichAppointment);
            return MiniPageVO.from(new PageInfo<>(records));
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PatientAppointmentVO getAppointment(Long userId, Long id) {
        PatientAppointmentVO appointment = patientQueryDao.selectPatientAppointmentDetail(userId, id);
        if (appointment == null) {
            throw appointmentDenied();
        }
        enrichAppointment(appointment);
        return appointment;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PatientRecordVO getConsultation(Long userId, Long appointmentId) {
        requireOwnedAppointment(userId, appointmentId);
        return patientQueryDao.selectPatientRecord(userId, appointmentId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PatientPrescriptionVO getPrescriptionByAppointment(Long userId, Long appointmentId) {
        requireOwnedAppointment(userId, appointmentId);
        Prescription prescription = patientQueryDao.selectPrescriptionByAppointment(appointmentId);
        if (prescription == null) {
            return null;
        }
        if (!userId.equals(prescription.getUserId())
                || !appointmentId.equals(prescription.getAppointmentId())) {
            throw prescriptionDenied();
        }
        return toPrescriptionVO(prescription);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PatientPrescriptionVO getPrescription(Long userId, Long prescriptionId) {
        Prescription prescription = patientQueryDao.selectPrescriptionByUser(userId, prescriptionId);
        if (prescription == null
                || patientQueryDao.selectOwnedAppointmentId(userId,
                prescription.getAppointmentId()) == null) {
            throw prescriptionDenied();
        }
        return toPrescriptionVO(prescription);
    }

    private String normalizeAppointmentFilter(String status) {
        if (status == null || status.isBlank() || "all".equals(status)) {
            return null;
        }
        if ("pending_dispense".equals(status) || "pending_pickup".equals(status)) {
            return status;
        }
        AppointmentStatus.validateFilter(status);
        return status;
    }

    private void enrichAppointment(PatientAppointmentVO appointment) {
        if (AppointmentStatus.PENDING_RX_PAY.equals(appointment.getStatus())
                && AppointmentStatus.RX_PAID.equals(appointment.getPrescriptionStatus())) {
            appointment.setStatusText(AppointmentStatus.label(AppointmentStatus.PENDING_PICKUP));
        } else {
            appointment.setStatusText(AppointmentStatus.label(appointment.getStatus()));
        }
        appointment.setPayMethodText(payMethodText(appointment.getPayMethod()));
    }

    private Long requireOwnedAppointment(Long userId, Long appointmentId) {
        Long owned = patientQueryDao.selectOwnedAppointmentId(userId, appointmentId);
        if (owned == null) {
            throw appointmentDenied();
        }
        return owned;
    }

    private PatientPrescriptionVO toPrescriptionVO(Prescription prescription) {
        PatientPrescriptionVO result = new PatientPrescriptionVO();
        result.setId(prescription.getId());
        result.setAppointmentId(prescription.getAppointmentId());
        result.setDoctorId(prescription.getDoctorId());
        result.setTotalAmount(prescription.getTotalAmount());
        result.setStatus(prescription.getStatus());
        result.setStatusText(prescriptionStatusText(prescription.getStatus()));
        result.setPayMethod(prescription.getPayMethod());
        result.setPayMethodText(payMethodText(prescription.getPayMethod()));
        result.setPayTime(prescription.getPayTime());
        result.setCreateTime(prescription.getCreateTime());
        result.setUpdateTime(prescription.getUpdateTime());
        result.setItems(patientQueryDao.selectPrescriptionItems(prescription.getId()));
        return result;
    }

    private Set<Long> availableDoctorIds(Long doctorId) {
        LocalDate today = LocalDate.now();
        List<PatientScheduleVO> windows = patientQueryDao.selectBookableScheduleWindows(
                today, today.plusDays(BOOKING_DAYS - 1L), doctorId);
        Set<Long> ids = new HashSet<>();
        for (PatientScheduleVO window : windows) {
            if (window.getRemainQuota() != null && window.getRemainQuota() > 0
                    && BookingTime.isBookable(window.getScheduleDate(), window.getTimeSlot())) {
                ids.add(window.getDoctorId());
            }
        }
        return ids;
    }

    private String payMethodText(String payMethod) {
        if (payMethod == null) {
            return "";
        }
        return switch (payMethod) {
            case "wechat" -> "微信支付";
            case "alipay" -> "支付宝";
            case "balance" -> "余额支付";
            default -> payMethod;
        };
    }

    private String prescriptionStatusText(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case AppointmentStatus.RX_UNPAID -> "待支付";
            case AppointmentStatus.RX_PAID -> "已支付";
            case AppointmentStatus.RX_DISPENSED -> "已发药";
            case AppointmentStatus.RX_CANCELLED -> "已取消";
            default -> status;
        };
    }

    private ResponseStatusException appointmentDenied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "预约不存在或无权访问");
    }

    private ResponseStatusException prescriptionDenied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "处方不存在或无权访问");
    }
}
