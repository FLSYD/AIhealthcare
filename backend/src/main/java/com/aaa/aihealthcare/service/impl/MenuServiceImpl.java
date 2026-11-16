package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.MenuPaths;
import com.aaa.aihealthcare.dao.MenuDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.SysMenu;
import com.aaa.aihealthcare.service.MenuService;
import com.aaa.aihealthcare.vo.MenuManageRequest;
import com.aaa.aihealthcare.vo.MenuManageVO;
import com.aaa.aihealthcare.vo.MenuVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Service
public class MenuServiceImpl implements MenuService {
    private static final int MAX_NAME_LENGTH = 50;
    private static final int MAX_ICON_LENGTH = 50;
    private static final int MAX_SORT_ORDER = 1_000_000;
    private static final int MAX_BATCH_SIZE = 100;
    private static final Set<String> ROLES = Set.of("ADMIN", "DOCTOR", "PHARMACIST");

    @Autowired
    private MenuDao menuDao;

    @Autowired
    private SysUserDao sysUserDao;

    @Override
    public List<MenuVO> listMine(String role) {
        if (!ROLES.contains(role)) {
            return List.of();
        }
        List<MenuVO> menus = new ArrayList<>();
        for (SysMenu menu : menuDao.selectAll()) {
            String canonicalPath = MenuPaths.tryCanonicalize(menu.getPath());
            // 路径允许的角色集合包含当前角色，且该菜单的角色配置也包含当前角色
            if (canonicalPath == null || !MenuPaths.allowsRole(canonicalPath, role)
                    || !parseRoles(menu.getRoles()).contains(role)) {
                continue;
            }
            menus.add(new MenuVO(MenuPaths.keyOf(canonicalPath), menu.getName(), canonicalPath,
                    menu.getIcon(), role, menu.getId(), menu.getParentId(),
                    menu.getSortOrder()));
        }
        menus.sort(Comparator
                .comparing(MenuVO::sortOrder, Comparator.nullsFirst(Integer::compareTo))
                .thenComparing(MenuVO::id, Comparator.nullsFirst(Long::compareTo)));
        return menus;
    }

    @Override
    public List<MenuManageVO> listManage(String keyword) {
        String normalizedKeyword = StringUtils.hasText(keyword)
                ? keyword.strip().toLowerCase(Locale.ROOT) : null;
        return menuDao.selectAll().stream()
                .map(this::toManageVO)
                .filter(menu -> matches(menu, normalizedKeyword))
                .sorted(Comparator
                        .comparing(MenuManageVO::sortOrder, Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(MenuManageVO::id, Comparator.nullsFirst(Long::compareTo)))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(MenuManageRequest request) {
        requireRequest(request);
        sysUserDao.lockActiveAdminIds();

        String path = MenuPaths.canonicalize(request.path());
        SysMenu menu = new SysMenu();
        menu.setParentId(validateParentId(request.parentId()));
        menu.setName(requireText(request.name(), MAX_NAME_LENGTH, "菜单名称"));
        menu.setPath(path);
        menu.setIcon(optionalText(request.icon(), MAX_ICON_LENGTH, "菜单图标"));
        menu.setSortOrder(validateSortOrder(request.sortOrder()));
        menu.setRoles(normalizeRoles(request.roles(), path));

        if (pathExists(menuDao.selectAllForUpdate(), path, null)) {
            throw new IllegalArgumentException("菜单路径已存在");
        }
        if (menuDao.insert(menu) != 1 || menu.getId() == null) {
            throw new IllegalStateException("新增菜单失败");
        }
        return menu.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long update(Long id, MenuManageRequest request) {
        requirePositiveId(id);
        requireRequest(request);
        sysUserDao.lockActiveAdminIds();
        SysMenu current = menuDao.selectByIdForUpdate(id);
        if (current == null) {
            throw new IllegalArgumentException("菜单不存在");
        }

        String currentPath = MenuPaths.tryCanonicalize(current.getPath());
        String requestedPath = MenuPaths.canonicalize(request.path());
        String roles = resolveRoles(request.roles(), requestedPath, currentPath, current.getRoles());
        if (MenuPaths.isDefaultHome(currentPath)
                && (!Objects.equals(currentPath, requestedPath)
                || !Objects.equals(MenuPaths.roleOf(requestedPath), roles))) {
            throw new IllegalArgumentException("默认首页的路径和角色不可修改");
        }

        if (pathExists(menuDao.selectAllForUpdate(), requestedPath, id)) {
            throw new IllegalArgumentException("菜单路径已存在");
        }
        current.setParentId(validateParentId(request.parentId()));
        current.setName(requireText(request.name(), MAX_NAME_LENGTH, "菜单名称"));
        current.setPath(storePath(current.getPath(), currentPath, requestedPath));
        current.setIcon(optionalText(request.icon(), MAX_ICON_LENGTH, "菜单图标"));
        current.setSortOrder(validateSortOrder(request.sortOrder()));
        current.setRoles(roles);
        if (menuDao.updateById(current) != 1) {
            throw new IllegalStateException("修改菜单失败");
        }
        return id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int delete(Long id) {
        requirePositiveId(id);
        sysUserDao.lockActiveAdminIds();
        SysMenu current = menuDao.selectByIdForUpdate(id);
        if (current == null) {
            return 0;
        }
        requireDeletable(current);
        if (menuDao.deleteById(id) != 1) {
            throw new IllegalStateException("删除菜单失败");
        }
        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteBatch(List<Long> ids) {
        List<Long> distinctIds = validateBatchIds(ids);
        sysUserDao.lockActiveAdminIds();
        List<SysMenu> menus = menuDao.selectByIdsForUpdate(distinctIds);
        if (menus.size() != distinctIds.size()) {
            throw new IllegalArgumentException("部分菜单不存在，批量删除已取消");
        }
        menus.forEach(this::requireDeletable);
        int affectedRows = menuDao.deleteBatchIds(distinctIds);
        if (affectedRows != distinctIds.size()) {
            throw new IllegalStateException("删除数量不一致，批量删除已取消");
        }
        return affectedRows;
    }

    private MenuManageVO toManageVO(SysMenu menu) {
        String canonicalPath = MenuPaths.tryCanonicalize(menu.getPath());
        return new MenuManageVO(menu.getId(), menu.getParentId(), menu.getName(),
                canonicalPath == null ? menu.getPath() : canonicalPath,
                menu.getIcon(), menu.getSortOrder(), menu.getRoles());
    }

    private boolean matches(MenuManageVO menu, String keyword) {
        if (keyword == null) {
            return true;
        }
        return contains(menu.name(), keyword) || contains(menu.path(), keyword)
                || contains(menu.icon(), keyword) || contains(menu.roles(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private boolean pathExists(List<SysMenu> menus, String path, Long excludeId) {
        for (SysMenu menu : menus) {
            if (excludeId != null && excludeId.equals(menu.getId())) {
                continue;
            }
            if (path.equals(MenuPaths.tryCanonicalize(menu.getPath()))) {
                return true;
            }
        }
        return false;
    }

    private void requireDeletable(SysMenu menu) {
        if (MenuPaths.isDefaultHome(MenuPaths.tryCanonicalize(menu.getPath()))) {
            throw new IllegalArgumentException("默认首页菜单不可删除");
        }
    }

    private List<Long> validateBatchIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("批量删除ids不能为空");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("批量删除不能超过 " + MAX_BATCH_SIZE + " 项");
        }
        TreeSet<Long> distinctIds = new TreeSet<>();
        for (Long id : ids) {
            if (id == null || id < 1) {
                throw new IllegalArgumentException("菜单id必须为正整数");
            }
            distinctIds.add(id);
        }
        return List.copyOf(distinctIds);
    }

    private String normalizeRoles(String roles, String path) {
        Set<String> allowedRoles = MenuPaths.rolesOf(path);
        if (allowedRoles.isEmpty()) {
            throw new IllegalArgumentException("该路径没有任何角色可以访问");
        }
        if (!StringUtils.hasText(roles)) {
            // 未指定时取路径允许的全部角色
            return String.join(",", allowedRoles);
        }
        String[] values = roles.split(",", -1);
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String value : values) {
            String role = value.strip();
            if (!ROLES.contains(role)) {
                throw new IllegalArgumentException("菜单角色只能包含 ADMIN、DOCTOR 或 PHARMACIST");
            }
            if (!allowedRoles.contains(role)) {
                throw new IllegalArgumentException("菜单角色不能超出该路径允许的角色");
            }
            parsed.add(role);
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("菜单角色不能为空");
        }
        return String.join(",", parsed);
    }

    private String resolveRoles(String requestedRoles, String requestedPath, String currentPath,
            String currentRoles) {
        if (StringUtils.hasText(requestedRoles)) {
            return normalizeRoles(requestedRoles, requestedPath);
        }
        if (Objects.equals(currentPath, requestedPath) && StringUtils.hasText(currentRoles)) {
            return normalizeRoles(currentRoles, requestedPath);
        }
        return MenuPaths.roleOf(requestedPath);
    }

    private Set<String> parseRoles(String roles) {
        if (!StringUtils.hasText(roles)) {
            return Set.of();
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String value : roles.split(",")) {
            String role = value.strip();
            if (ROLES.contains(role)) {
                parsed.add(role);
            }
        }
        return parsed;
    }

    private String storePath(String currentStoredPath, String currentCanonicalPath,
            String requestedCanonicalPath) {
        if (Objects.equals(currentCanonicalPath, requestedCanonicalPath)
                && MenuPaths.isLegacyPath(currentStoredPath)) {
            return currentStoredPath.strip();
        }
        return requestedCanonicalPath;
    }

    private void requireRequest(MenuManageRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("菜单信息不能为空");
        }
    }

    private long validateParentId(Long parentId) {
        long value = parentId == null ? 0L : parentId;
        if (value != 0L) {
            throw new IllegalArgumentException("当前仅支持 parentId 为 0 的平级菜单");
        }
        return value;
    }

    private int validateSortOrder(Integer sortOrder) {
        int value = sortOrder == null ? 0 : sortOrder;
        if (value < 0 || value > MAX_SORT_ORDER) {
            throw new IllegalArgumentException("排序值必须在 0 到 " + MAX_SORT_ORDER + " 之间");
        }
        return value;
    }

    private String requireText(String value, int maxLength, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String trimmed = value.strip();
        if (textLength(trimmed) > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private String optionalText(String value, int maxLength, String label) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.strip();
        if (textLength(trimmed) > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private int textLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private void requirePositiveId(Long id) {
        if (id == null || id < 1) {
            throw new IllegalArgumentException("菜单id必须为正整数");
        }
    }
}
