package com.aaa.aihealthcare.vo;

import com.aaa.aihealthcare.entity.Schedule;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BookingSlotVO extends Schedule {
    private Integer doctorStatus;
    private Integer departmentStatus;
    private String doctorRoleType;
    private String departmentType;
}
