package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.DepartmentDao;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.entity.Doctor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorServiceImplTests {

    @Mock
    private DoctorDao doctorDao;

    @Mock
    private DepartmentDao departmentDao;

    @Mock
    private SysUserDao sysUserDao;

    @InjectMocks
    private DoctorServiceImpl service;

    @Test
    void deleteRejectsHistoricalUserReferenceBeforePhysicallyDeleting() {
        Doctor current = new Doctor();
        current.setId(8L);
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(doctorDao.selectByIdForUpdate(8L)).thenReturn(current);
        when(doctorDao.countSchedulesByDoctorId(8L)).thenReturn(0L);
        when(doctorDao.countUserReferencesByDoctorId(8L)).thenReturn(1L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.deleteById(8L));

        assertEquals("该医生仍被 1 个账号引用，无法删除", exception.getMessage());
        InOrder order = inOrder(sysUserDao, doctorDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(doctorDao).selectByIdForUpdate(8L);
        order.verify(doctorDao).countSchedulesByDoctorId(8L);
        order.verify(doctorDao).countUserReferencesByDoctorId(8L);
        verify(doctorDao, never()).deleteById(8L);
    }

    @Test
    void updateLocksDoctorThenDepartment() {
        Doctor current = new Doctor();
        current.setId(8L);
        Doctor request = new Doctor();
        request.setId(8L);
        request.setDepartmentId(2L);
        request.setName(" 李医生 ");
        request.setGender(2);
        request.setStatus(1);
        Department department = new Department();
        department.setId(2L);
        department.setStatus(1);
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(doctorDao.selectByIdForUpdate(8L)).thenReturn(current);
        when(departmentDao.selectByIdForUpdate(2L)).thenReturn(department);
        when(doctorDao.updateById(request)).thenReturn(1);

        assertTrue(service.update(request));

        assertEquals("李医生", request.getName());
        InOrder order = inOrder(sysUserDao, doctorDao, departmentDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(doctorDao).selectByIdForUpdate(8L);
        order.verify(departmentDao).selectByIdForUpdate(2L);
        order.verify(doctorDao).updateById(request);
    }

    @Test
    void saveDefaultsGenderToMaleAndAcceptsFemale() {
        Doctor doctor = validDoctor(null);
        mockEnabledDepartment();
        when(doctorDao.insert(doctor)).thenReturn(1);

        assertTrue(service.save(doctor));
        assertEquals(1, doctor.getGender());

        Doctor female = validDoctor(2);
        mockEnabledDepartment();
        when(doctorDao.insert(female)).thenReturn(1);
        assertTrue(service.save(female));
        assertEquals(2, female.getGender());
    }

    @Test
    void saveRejectsGenderOutsideRealDatabaseCodes() {
        Doctor doctor = validDoctor(0);
        mockEnabledDepartment();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.save(doctor));

        assertEquals("性别值只能为 1 或 2", exception.getMessage());
        verify(doctorDao, never()).insert(doctor);
    }

    private Doctor validDoctor(Integer gender) {
        Doctor doctor = new Doctor();
        doctor.setDepartmentId(2L);
        doctor.setName("李医生");
        doctor.setGender(gender);
        doctor.setStatus(1);
        return doctor;
    }

    private void mockEnabledDepartment() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        Department department = new Department();
        department.setId(2L);
        department.setStatus(1);
        when(departmentDao.selectByIdForUpdate(2L)).thenReturn(department);
    }
}
