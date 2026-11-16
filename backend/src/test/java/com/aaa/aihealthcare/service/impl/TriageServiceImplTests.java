package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.PatientQueryDao;
import com.aaa.aihealthcare.entity.Department;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TriageServiceImplTests {
    @Mock PatientQueryDao dao;
    @InjectMocks TriageServiceImpl service;

    private void prepare() {
        Department department = new Department(); department.setId(1L); department.setName("内科"); department.setStatus(1);
        when(dao.selectActiveDepartments()).thenReturn(List.of(department));
    }
    @Test
    void insomniaAnalysisDoesNotClaimOtherRuleSymptomsWereReported() {
        prepare(); var response = service.analyze("失眠");
        assertEquals("描述中识别到：失眠。", response.getSymptomAnalysis());
        assertTrue(response.getCommonSigns().contains("头痛"));
        assertEquals("fallback", response.getSource());
    }
    @Test
    void coughAnalysisDoesNotClaimFeverWasReported() {
        prepare(); var response = service.analyze("咳嗽");
        assertEquals("描述中识别到：咳嗽。", response.getSymptomAnalysis());
        assertFalse(response.getSymptomAnalysis().contains("发热"));
    }
    @Test
    void urgentKeywordsAreActualObservationsAndPreserveUrgency() {
        prepare(); var response = service.analyze("呼吸困难");
        assertEquals("描述中识别到：呼吸困难。", response.getSymptomAnalysis());
        assertTrue(response.isUrgent());
    }
}
