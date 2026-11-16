package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.LoginSession;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysUserServiceImplTests {

    @Mock
    private SysUserDao sysUserDao;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private DoctorDao doctorDao;

    @InjectMocks
    private SysUserServiceImpl service;

    @Test
    void insertingDoctorLocksAdminsBeforeValidatingDoctorAndInsert() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(9L));
        Doctor doctor = new Doctor();
        doctor.setId(5L);
        doctor.setStatus(1);
        doctor.setRoleType("DOCTOR");
        when(doctorDao.selectByIdForUpdate(5L)).thenReturn(doctor);
        var department = new com.aaa.aihealthcare.entity.Department();
        department.setType("CLINICAL"); department.setStatus(1);
        when(doctorDao.selectDepartmentForUpdate(5L)).thenReturn(department);
        SysUser request = new SysUser();
        request.setUsername("doctor-user");
        request.setPassword("password");
        request.setRole("DOCTOR");
        request.setDoctorId(5L);
        request.setStatus(1);
        when(sysUserDao.insert(request)).thenReturn(1);

        assertEquals(1, service.insert(request));

        InOrder inOrder = inOrder(sysUserDao, doctorDao);
        inOrder.verify(sysUserDao).lockActiveAdminIds();
        inOrder.verify(doctorDao).selectByIdForUpdate(5L);
        inOrder.verify(sysUserDao).insert(request);
    }

    @Test
    void lastActiveAdminCannotBeDisabled() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(sysUserDao.getByIdForUpdate(1L)).thenReturn(user(1L, "ADMIN", 1));
        SysUser request = request(1L, "ADMIN", 0);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.update(request));

        assertEquals("至少保留一名启用的管理员", exception.getMessage());
        verifyLockOrder(1L);
        verifyNoWriteSql();
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void lastActiveAdminCannotBeDowngraded() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(sysUserDao.getByIdForUpdate(1L)).thenReturn(user(1L, "ADMIN", 1));
        SysUser request = request(1L, "USER", 1);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.update(request));

        assertEquals("至少保留一名启用的管理员", exception.getMessage());
        verifyLockOrder(1L);
        verifyNoWriteSql();
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void lastActiveAdminCannotBeDeleted() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(sysUserDao.getByIdForUpdate(1L)).thenReturn(user(1L, "ADMIN", 1));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.delete(1L));

        assertEquals("至少保留一名启用的管理员", exception.getMessage());
        verifyLockOrder(1L);
        verifyNoWriteSql();
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void batchDeleteCannotRemoveAllActiveAdmins() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(sysUserDao.getByIdForUpdate(1L)).thenReturn(user(1L, "ADMIN", 1));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.batchDelete(List.of(1L, 1L)));

        assertEquals("至少保留一名启用的管理员", exception.getMessage());
        verifyLockOrder(1L);
        verifyNoWriteSql();
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void deletingRegularUserRevokesSessionBeforeLogicalDelete() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(9L));
        when(sysUserDao.getByIdForUpdate(2L)).thenReturn(user(2L, "USER", 1));
        when(sysUserDao.delete(2L)).thenReturn(1);
        stubRedis();

        assertEquals(1, service.delete(2L));

        verifyLockOrder(2L);
        verify(valueOperations).set(eq(LoginSession.REVISION_PREFIX + 2L), anyString());
        verify(sysUserDao).delete(2L);
    }

    @Test
    void deletingSecondActiveAdminIsAllowedAndRevokesSession() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L, 2L));
        when(sysUserDao.getByIdForUpdate(2L)).thenReturn(user(2L, "ADMIN", 1));
        when(sysUserDao.delete(2L)).thenReturn(1);
        stubRedis();

        assertEquals(1, service.delete(2L));

        verifyLockOrder(2L);
        verify(valueOperations).set(eq(LoginSession.REVISION_PREFIX + 2L), anyString());
        verify(sysUserDao).delete(2L);
    }

    @Test
    void batchDeleteWithMissingUserLocksInOrderAndDoesNotPartiallyDelete() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(9L));
        when(sysUserDao.getByIdForUpdate(2L)).thenReturn(user(2L, "USER", 1));
        when(sysUserDao.getByIdForUpdate(3L)).thenReturn(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.batchDelete(List.of(3L, 2L, 3L)));

        assertEquals("部分用户不存在，批量删除已取消", exception.getMessage());
        verifyLockOrder(2L, 3L);
        verifyNoWriteSql();
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void batchDeleteDeduplicatesAndRevokesEachSessionOnce() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(9L));
        when(sysUserDao.getByIdForUpdate(2L)).thenReturn(user(2L, "USER", 1));
        when(sysUserDao.getByIdForUpdate(3L)).thenReturn(user(3L, "USER", 1));
        when(sysUserDao.batchDelete(List.of(2L, 3L))).thenReturn(2);
        stubRedis();

        assertEquals(2, service.batchDelete(List.of(3L, 2L, 3L)));

        verifyLockOrder(2L, 3L);
        verify(valueOperations).set(eq(LoginSession.REVISION_PREFIX + 2L), anyString());
        verify(valueOperations).set(eq(LoginSession.REVISION_PREFIX + 3L), anyString());
        verify(sysUserDao).batchDelete(List.of(2L, 3L));
    }

    @Test
    void redisFailureStopsLogicalDelete() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(9L));
        when(sysUserDao.getByIdForUpdate(2L)).thenReturn(user(2L, "USER", 1));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(valueOperations).set(anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> service.delete(2L));

        verifyLockOrder(2L);
        verify(sysUserDao, never()).delete(2L);
    }

    private void stubRedis() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private void verifyLockOrder(Long... targetIds) {
        InOrder inOrder = inOrder(sysUserDao);
        inOrder.verify(sysUserDao).lockActiveAdminIds();
        for (Long targetId : targetIds) {
            inOrder.verify(sysUserDao).getByIdForUpdate(targetId);
        }
    }

    private void verifyNoWriteSql() {
        verify(sysUserDao, never()).update(any(SysUser.class));
        verify(sysUserDao, never()).delete(any(Long.class));
        verify(sysUserDao, never()).batchDelete(any());
    }

    private SysUser user(Long id, String role, int status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole(role);
        user.setStatus(status);
        user.setDeleted(0);
        return user;
    }

    private SysUser request(Long id, String role, int status) {
        SysUser request = new SysUser();
        request.setId(id);
        request.setRole(role);
        request.setStatus(status);
        return request;
    }
}
