package com.aaa.aihealthcare.vo;

import lombok.Data;

@Data
public class DoctorWorkbenchVO {

    private DoctorVO doctor;
    private Long todayAppointments;
    private Long pendingAppointments;
    private Long completedAppointments;
}
