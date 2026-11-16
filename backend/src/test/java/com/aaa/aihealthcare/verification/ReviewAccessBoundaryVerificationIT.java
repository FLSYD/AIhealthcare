package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.entity.Schedule;
import com.aaa.aihealthcare.service.PharmacyService;
import com.aaa.aihealthcare.service.impl.PharmacyServiceImpl;
import com.aaa.aihealthcare.vo.AppointmentCreateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 复检新发现的访问边界，仅通过专属 MySQL 与合成数据验证，业务未修复时契约应失败。 */
@SpringJUnitConfig({IsolatedMysqlConfiguration.class, PharmacyServiceImpl.class})
class ReviewAccessBoundaryVerificationIT extends MysqlVerificationSupport {
    @Autowired PharmacyService pharmacy;

    @ParameterizedTest
    @ValueSource(strings = {"unpaid", "cancelled"})
    void pharmacyDetailRejectsStatesOutsideItsWorklist(String state) {
        prescriptionFixture(state);
        assertEquals(0, pharmacy.page("paid", null, 1, 10).getList().size());
        assertEquals(0, pharmacy.page("dispensed", null, 1, 10).getList().size());
        assertThrows(IllegalArgumentException.class, () -> pharmacy.detail(300L),
                "药师列表不可见的" + state + "处方仍可直接按id读取患者及药品信息");
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", "dispensed"})
    void pharmacyCanReadLegitimateWorklistStates(String state) {
        prescriptionFixture(state);
        assertEquals(1, pharmacy.page(state, null, 1, 10).getList().size());
        var result = pharmacy.detail(300L);
        assertEquals(state, result.getStatus());
        assertEquals("Synthetic patient", result.getPatientName());
        assertEquals(1, result.getItems().size());
    }

    @Test
    void patientCannotBookAPharmacistWhoseStaffAndDepartmentTypesAreConsistent() {
        Schedule schedule = new Schedule();
        schedule.setDoctorId(2L);
        schedule.setScheduleDate(LocalDate.now().plusDays(1));
        schedule.setTimeSlot("09:00");
        schedule.setTotalQuota(2);
        schedule.setStatus(1);
        try {
            if (schedules.save(schedule)) {
                patient.create(10L, new AppointmentCreateRequest(schedule.getId(), "Synthetic patient", "13800000000", null));
            }
        } catch (IllegalArgumentException expectedRejection) {
            // 可以在排班或患者建单阶段拒绝，但必须检查持久化结果没有非法预约。
        }
        long invalidBookings = count("SELECT COUNT(*) FROM appointment a JOIN schedule s ON s.id=a.schedule_id JOIN doctor d ON d.id=s.doctor_id WHERE d.role_type='PHARMACIST'");
        System.out.println("REVIEW_ACCESS_EVIDENCE pharmacistPatientBookings=" + invalidBookings);
        assertEquals(0, invalidBookings, "人员和科室均为配药类型，仍能创建患者预约并占号");
    }

    @Test
    void patientCanStillBookAnOrdinaryClinicalDoctor() {
        slot(100L, 2, 0);
        var result = patient.create(10L, new AppointmentCreateRequest(100L, "Synthetic patient", "13800000000", null));
        assertNotNull(result.getId());
        assertEquals(1, count("SELECT COUNT(*) FROM appointment WHERE user_id=10 AND schedule_id=100"));
        assertEquals(1, count("SELECT booked_count FROM schedule WHERE id=100"));
    }

    private void prescriptionFixture(String state) {
        slot(100L, 2, 1);
        order(200L, 10L, 100L, "dispensed".equals(state) ? "completed" : "pending_rx_pay", true, 0);
        medicine(5);
        paidPrescription(300L, 200L, 10L, 1);
        jdbc.update("UPDATE prescription SET status=? WHERE id=300", state);
        if ("unpaid".equals(state) || "cancelled".equals(state)) {
            jdbc.update("UPDATE prescription SET pay_method=NULL,pay_time=NULL WHERE id=300");
        }
    }
}
