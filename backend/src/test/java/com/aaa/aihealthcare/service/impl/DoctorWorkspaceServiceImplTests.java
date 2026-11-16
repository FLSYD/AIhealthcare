package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.DoctorWorkspaceDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.vo.DoctorVO;
import com.aaa.aihealthcare.vo.IntroUpdateVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorWorkspaceServiceImplTests {

    @Mock
    private DoctorDao doctorDao;

    @Mock
    private DoctorWorkspaceDao doctorWorkspaceDao;

    @Mock
    private SysUserDao sysUserDao;

    @InjectMocks
    private DoctorWorkspaceServiceImpl service;

    @Test
    void profileUsesBoundActiveDoctor() {
        SysUser currentUser = doctorUser(8L);
        Doctor doctor = activeDoctor(8L);
        DoctorVO profile = new DoctorVO();
        profile.setId(8L);
        when(doctorDao.selectById(8L)).thenReturn(doctor);
        when(doctorWorkspaceDao.selectDoctor(8L)).thenReturn(profile);

        assertSame(profile, service.getProfile(currentUser));
        verify(doctorWorkspaceDao).selectDoctor(8L);
    }

    @Test
    void introUpdateLocksAdminThenDoctorAndUpdatesOnlyIntro() {
        SysUser currentUser = doctorUser(8L);
        Doctor doctor = activeDoctor(8L);
        DoctorVO profile = new DoctorVO();
        profile.setId(8L);
        profile.setIntro("专长心血管疾病");
        IntroUpdateVO request = new IntroUpdateVO();
        request.setIntro("专长心血管疾病");
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(doctorDao.selectByIdForUpdate(8L)).thenReturn(doctor);
        when(doctorDao.updateIntro(eq(8L), eq("专长心血管疾病"), any(LocalDateTime.class))).thenReturn(1);
        when(doctorWorkspaceDao.selectDoctor(8L)).thenReturn(profile);

        assertSame(profile, service.updateIntro(currentUser, request));

        InOrder order = inOrder(sysUserDao, doctorDao, doctorWorkspaceDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(doctorDao).selectByIdForUpdate(8L);
        order.verify(doctorDao).updateIntro(eq(8L), eq("专长心血管疾病"), any(LocalDateTime.class));
        order.verify(doctorWorkspaceDao).selectDoctor(8L);
    }

    @Test
    void introOverOneThousandCodePointsIsRejectedBeforeLocking() {
        IntroUpdateVO request = new IntroUpdateVO();
        request.setIntro("医".repeat(1001));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.updateIntro(doctorUser(8L), request));

        assertEquals("医生简介长度不能超过 1000", exception.getMessage());
        verify(sysUserDao, never()).lockActiveAdminIds();
    }

    @Test
    void unboundDoctorAccountIsForbidden() {
        SysUser user = doctorUser(null);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.getProfile(user));
        verify(doctorWorkspaceDao, never()).selectDoctor(any());
    }

    private SysUser doctorUser(Long doctorId) {
        SysUser user = new SysUser();
        user.setRole("DOCTOR");
        user.setStatus(1);
        user.setDeleted(0);
        user.setDoctorId(doctorId);
        return user;
    }

    private Doctor activeDoctor(Long id) {
        Doctor doctor = new Doctor();
        doctor.setId(id);
        doctor.setStatus(1);
        return doctor;
    }
}
