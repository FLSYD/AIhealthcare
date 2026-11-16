package com.aaa.aihealthcare.vo;

public record AppointmentCreateRequest(Long scheduleId, String patientName, String patientPhone, String remark) {
}
