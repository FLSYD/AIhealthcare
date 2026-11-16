package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.DepartmentDao;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Department;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepartmentServiceImplTests {

    @Mock
    private DepartmentDao departmentDao;

    @Mock
    private DoctorDao doctorDao;

    @Mock
    private SysUserDao sysUserDao;

    @InjectMocks
    private DepartmentServiceImpl service;

    @Test
    void saveAcquiresGlobalLockBeforeNameCheckAndInsert() {
        Department request = new Department();
        request.setName(" 内科 ");
        request.setStatus(1);
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(departmentDao.selectCount(any())).thenReturn(0L);
        when(departmentDao.insert(request)).thenReturn(1);

        assertTrue(service.save(request));

        assertEquals("内科", request.getName());
        InOrder order = inOrder(sysUserDao, departmentDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(departmentDao).selectCount(any());
        order.verify(departmentDao).insert(request);
    }

    @Test
    void deleteLocksDepartmentBeforeCheckingDoctorReference() {
        Department current = new Department();
        current.setId(3L);
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(departmentDao.selectByIdForUpdate(3L)).thenReturn(current);
        when(doctorDao.selectCount(any())).thenReturn(0L);
        when(departmentDao.deleteById(3L)).thenReturn(1);

        assertTrue(service.deleteById(3L));

        InOrder order = inOrder(sysUserDao, departmentDao, doctorDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(departmentDao).selectByIdForUpdate(3L);
        order.verify(doctorDao).selectCount(any());
        order.verify(departmentDao).deleteById(3L);
    }
}
