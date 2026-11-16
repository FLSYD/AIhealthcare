package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.MenuDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.SysMenu;
import com.aaa.aihealthcare.vo.MenuManageRequest;
import com.aaa.aihealthcare.vo.MenuVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuServiceImplTests {
    @Mock
    private MenuDao menuDao;

    @Mock
    private SysUserDao sysUserDao;

    @InjectMocks
    private MenuServiceImpl service;

    @Test
    void legalCreateDefaultsRoleAndReturnsEntityId() {
        MenuManageRequest request = request(" 数据概览 ", "/dashboard", 0L, "DataLine", 0, null);
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectAllForUpdate()).thenReturn(List.of());
        when(menuDao.insert(any(SysMenu.class))).thenAnswer(invocation -> {
            SysMenu menu = invocation.getArgument(0);
            menu.setId(8L);
            assertEquals("ADMIN", menu.getRoles());
            assertEquals("/dashboard", menu.getPath());
            return 1;
        });

        assertEquals(8L, service.create(request));

        InOrder order = inOrder(sysUserDao, menuDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(menuDao).selectAllForUpdate();
        order.verify(menuDao).insert(any(SysMenu.class));
    }

    @Test
    void roleEscalationIsRejectedForAdminPage() {
        MenuManageRequest request = request("科室管理", "/department", 0L, null, 1, "USER");
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.create(request));

        assertEquals("菜单角色只能包含 ADMIN、DOCTOR 或 PHARMACIST", exception.getMessage());
        verify(menuDao, never()).insert(any(SysMenu.class));
    }

    @Test
    void legacyAliasCannotDuplicateCanonicalPath() {
        SysMenu existing = menu(1L, "/admin/dashboard", "ADMIN", 0);
        MenuManageRequest request = request("重复首页", "/dashboard", 0L, null, 2, "ADMIN");
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectAllForUpdate()).thenReturn(List.of(existing));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.create(request));

        assertEquals("菜单路径已存在", exception.getMessage());
        verify(menuDao, never()).insert(any(SysMenu.class));
    }

    @Test
    void unknownOrExternalPathIsRejected() {
        MenuManageRequest request = request("外链", "https://example.com", 0L, null, 0, "ADMIN");
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.create(request));

        assertEquals("不支持或未注册的菜单路径", exception.getMessage());
        verify(menuDao, never()).insert(any(SysMenu.class));
    }

    @Test
    void defaultHomeCannotBeDeleted() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectByIdForUpdate(1L)).thenReturn(
                menu(1L, "/admin/dashboard", "ADMIN", 0));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.delete(1L));

        assertEquals("默认首页菜单不可删除", exception.getMessage());
        verify(menuDao, never()).deleteById(any(Long.class));
    }

    @Test
    void legalNonHomeMenuCanBeDeleted() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectByIdForUpdate(3L)).thenReturn(menu(3L, "/doctor", "ADMIN", 2));
        when(menuDao.deleteById(3L)).thenReturn(1);

        assertEquals(1, service.delete(3L));

        InOrder order = inOrder(sysUserDao, menuDao);
        order.verify(sysUserDao).lockActiveAdminIds();
        order.verify(menuDao).selectByIdForUpdate(3L);
        order.verify(menuDao).deleteById(3L);
    }

    @Test
    void defaultHomeCannotChangePathOrRole() {
        SysMenu current = menu(10L, "/admin/doctor/dashboard", "DOCTOR", 0);
        MenuManageRequest request = request("工作概览", "/doctor/schedules", 0L, null, 0, "DOCTOR");
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectByIdForUpdate(10L)).thenReturn(current);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.update(10L, request));

        assertEquals("默认首页的路径和角色不可修改", exception.getMessage());
        verify(menuDao, never()).updateById(any(SysMenu.class));
    }

    @Test
    void missingRolesOnPathChangeUsesFinalPathRole() {
        SysMenu current = menu(2L, "/admin/departments", "ADMIN", 3);
        MenuManageRequest request = request("我的排班", "/doctor/schedules", 0L, null, 4, null);
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectByIdForUpdate(2L)).thenReturn(current);
        when(menuDao.selectAllForUpdate()).thenReturn(List.of(current));
        when(menuDao.updateById(current)).thenReturn(1);

        assertEquals(2L, service.update(2L, request));
        assertEquals("/doctor/schedules", current.getPath());
        assertEquals("DOCTOR", current.getRoles());
    }

    @Test
    void pathChangeRejectsRoleFromAnotherRoute() {
        SysMenu current = menu(2L, "/admin/departments", "ADMIN", 3);
        MenuManageRequest request = request("我的排班", "/doctor/schedules", 0L, null, 4, "ADMIN");
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectByIdForUpdate(2L)).thenReturn(current);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.update(2L, request));

        assertEquals("菜单角色不能超出该路径允许的角色", exception.getMessage());
        verify(menuDao, never()).updateById(any(SysMenu.class));
    }

    @Test
    void updateKeepsLegacyStoragePathWhileReturningId() {
        SysMenu current = menu(2L, "/admin/departments", "ADMIN", 3);
        MenuManageRequest request = request("科室中心", "/department", 0L, "Office", 4, null);
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectByIdForUpdate(2L)).thenReturn(current);
        when(menuDao.selectAllForUpdate()).thenReturn(List.of(current));
        when(menuDao.updateById(current)).thenReturn(1);

        assertEquals(2L, service.update(2L, request));
        assertEquals("/admin/departments", current.getPath());
        assertEquals("科室中心", current.getName());
        assertEquals("ADMIN", current.getRoles());
    }

    @Test
    void batchDeleteIsAtomicWhenOneIdIsMissing() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectByIdsForUpdate(List.of(2L, 3L, 4L)))
                .thenReturn(List.of(menu(2L, "/department", "ADMIN", 1),
                        menu(3L, "/doctor", "ADMIN", 2)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.deleteBatch(List.of(4L, 3L, 2L, 3L)));

        assertEquals("部分菜单不存在，批量删除已取消", exception.getMessage());
        verify(menuDao, never()).deleteBatchIds(any());
    }

    @Test
    void batchDeleteRejectsDefaultHomeBeforeDeletingAnything() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectByIdsForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(menu(1L, "/admin/dashboard", "ADMIN", 0),
                        menu(2L, "/department", "ADMIN", 1)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.deleteBatch(List.of(1L, 2L)));

        assertEquals("默认首页菜单不可删除", exception.getMessage());
        verify(menuDao, never()).deleteBatchIds(any());
    }

    @Test
    void batchDeleteLocksOrderedDistinctIdsAndDeletesAll() {
        when(sysUserDao.lockActiveAdminIds()).thenReturn(List.of(1L));
        when(menuDao.selectByIdsForUpdate(List.of(2L, 3L)))
                .thenReturn(List.of(menu(2L, "/department", "ADMIN", 1),
                        menu(3L, "/doctor", "ADMIN", 2)));
        when(menuDao.deleteBatchIds(List.of(2L, 3L))).thenReturn(2);

        assertEquals(2, service.deleteBatch(List.of(3L, 2L, 3L)));
        verify(menuDao).selectByIdsForUpdate(List.of(2L, 3L));
        verify(menuDao).deleteBatchIds(List.of(2L, 3L));
    }

    @Test
    void mineMenusAreSortedAndLegacyPathsAreCanonical() {
        SysMenu later = menu(5L, "/admin/medicines", "ADMIN", 8);
        SysMenu earlier = menu(4L, "/admin/departments", "ADMIN", 2);
        SysMenu doctor = menu(6L, "/admin/doctor/dashboard", "DOCTOR", 0);
        when(menuDao.selectAll()).thenReturn(List.of(later, doctor, earlier));

        List<MenuVO> menus = service.listMine("ADMIN");

        assertEquals(List.of("/department", "/medicine"), menus.stream().map(MenuVO::path).toList());
        assertEquals("department", menus.get(0).key());
        assertEquals("ADMIN", menus.get(0).roles());
    }

    @Test
    void mineMenusRejectRowsWithRoleOutsideRoutePolicy() {
        SysMenu adminPathWithDoctorRole = menu(7L, "/admin/users", "DOCTOR", 1);
        SysMenu doctorPathWithAdminRole = menu(8L, "/admin/doctor/dashboard", "ADMIN", 0);
        when(menuDao.selectAll()).thenReturn(List.of(adminPathWithDoctorRole, doctorPathWithAdminRole));

        assertEquals(List.of(), service.listMine("ADMIN"));
        assertEquals(List.of(), service.listMine("DOCTOR"));
    }

    @Test
    void userRoleAlwaysReceivesNoMenus() {
        assertEquals(List.of(), service.listMine("USER"));
        verify(menuDao, never()).selectAll();
    }

    private MenuManageRequest request(String name, String path, Long parentId, String icon,
            Integer sortOrder, String roles) {
        return new MenuManageRequest(name, path, parentId, icon, sortOrder, roles);
    }

    private SysMenu menu(Long id, String path, String roles, Integer sortOrder) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(0L);
        menu.setName("菜单" + id);
        menu.setPath(path);
        menu.setSortOrder(sortOrder);
        menu.setRoles(roles);
        return menu;
    }
}
