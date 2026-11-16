package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.common.LoginSession;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.service.SysUserService;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.SysUser;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

@Service
public class SysUserServiceImpl implements SysUserService {

    private static final String LAST_ACTIVE_ADMIN_MESSAGE = "至少保留一名启用的管理员";
    private static final String DOCTOR_ROLE = "DOCTOR";
    private static final String PHARMACIST_ROLE = "PHARMACIST";

    @Autowired
    private SysUserDao sysUserDao;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private DoctorDao doctorDao;

    private final BCryptPasswordEncoder passwordEncoder =
            new BCryptPasswordEncoder();

    @Override
    public PageInfo<SysUser> queryByCondition(Integer pageNum, Integer pageSize, SysUser sysUser) {
        // 与其他模块统一使用 PageUtils（内含缺省值与 pageSize<=100 上限）。
        PageUtils.startPage(pageNum, pageSize);
        try {
            List<SysUser> users = sysUserDao.queryByCondition(sysUser);
            return new PageInfo<>(users);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public SysUser getById(Long id) {
        return sysUserDao.getById(id);
    }

    @Override
    public SysUser selectByUsername(String username) {
        return sysUserDao.selectByUsername(username);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insert(SysUser user) {
        if (user == null) {
            return 0;
        }
        sysUserDao.lockActiveAdminIds();
        Doctor doctor = validateDoctorBinding(user.getRole(), user.getDoctorId());
        boolean enabledDoctorAccount = isDoctorLinkedRole(user.getRole())
                && Integer.valueOf(1).equals(user.getStatus());
        boolean restoringDoctor = enabledDoctorAccount
                && !Integer.valueOf(1).equals(doctor.getStatus());
        if (restoringDoctor) {
            requireActiveDoctorDepartment(doctor);
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        Date now = new Date();
        user.setCreateTime(now);
        user.setUpdateTime(now);
        int affectedRows = sysUserDao.insert(user);
        if (affectedRows > 0 && restoringDoctor) {
            enableDoctorAfterAccountWrite(doctor);
        }
        return affectedRows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int update(SysUser user) {
        if (user == null || user.getId() == null) {
            return 0;
        }
        List<Long> activeAdminIds = sysUserDao.lockActiveAdminIds();
        SysUser existingUser = sysUserDao.getByIdForUpdate(user.getId());
        if (existingUser == null) {
            return 0;
        }
        String effectiveRole = user.getRole() == null ? existingUser.getRole() : user.getRole();
        Integer effectiveStatus = user.getStatus() == null ? existingUser.getStatus() : user.getStatus();
        ensureActiveAdminRemainsForUpdate(existingUser, effectiveRole, effectiveStatus, activeAdminIds);
        if (!isDoctorLinkedRole(effectiveRole) && user.getDoctorId() != null) {
            throw new IllegalArgumentException("只有医生或配药师账号可以关联医生档案");
        }
        Long effectiveDoctorId = resolveEffectiveDoctorId(user, existingUser, effectiveRole);
        Doctor boundDoctor = validateDoctorBinding(effectiveRole, effectiveDoctorId);
        boolean roleChanged = user.getRole() != null && !user.getRole().equals(existingUser.getRole());
        boolean bindingChanged = !Objects.equals(effectiveDoctorId, existingUser.getDoctorId());
        boolean accountEnabled = Integer.valueOf(1).equals(effectiveStatus);
        boolean enablingAccount = accountEnabled
                && !Integer.valueOf(1).equals(existingUser.getStatus());
        boolean restoringDoctor = accountEnabled && (enablingAccount || bindingChanged)
                && boundDoctor != null
                && !Integer.valueOf(1).equals(boundDoctor.getStatus());
        if (restoringDoctor) {
            requireActiveDoctorDepartment(boundDoctor);
        }
        user.setRole(effectiveRole);
        user.setDoctorId(effectiveDoctorId);
        if ((user.getStatus() != null && !user.getStatus().equals(existingUser.getStatus()))
                || roleChanged || bindingChanged || Integer.valueOf(0).equals(user.getStatus())) {
            // 状态、角色或医生绑定变化时撤销旧会话；Redis 失败时停止修改。
            stringRedisTemplate.opsForValue().set(LoginSession.REVISION_PREFIX + user.getId(),
                    UUID.randomUUID().toString());
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            user.setPassword(null);
        } else {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        user.setUpdateTime(new Date());
        int affectedRows = sysUserDao.update(user);
        if (affectedRows <= 0) {
            return affectedRows;
        }
        if (restoringDoctor) {
            enableDoctorAfterAccountWrite(boundDoctor);
        }
        if (existingUser.getDoctorId() != null
                && (Integer.valueOf(0).equals(effectiveStatus)
                || !isDoctorLinkedRole(effectiveRole)
                || !Objects.equals(effectiveDoctorId, existingUser.getDoctorId()))) {
            disableDoctorIfNoActiveAccounts(existingUser.getDoctorId());
        }
        return affectedRows;
    }

    private void ensureActiveAdminRemainsForUpdate(SysUser existingUser, String effectiveRole,
            Integer effectiveStatus, List<Long> activeAdminIds) {
        boolean removesSoleActiveAdmin = activeAdminIds.size() == 1
                && activeAdminIds.get(0).equals(existingUser.getId())
                && (!"ADMIN".equals(effectiveRole) || !Integer.valueOf(1).equals(effectiveStatus));
        if (removesSoleActiveAdmin) {
            throw new IllegalArgumentException(LAST_ACTIVE_ADMIN_MESSAGE);
        }
    }

    /** 可关联医生档案的角色：医生与配药师。 */
    private static boolean isDoctorLinkedRole(String role) {
        return DOCTOR_ROLE.equals(role) || PHARMACIST_ROLE.equals(role);
    }

    private Long resolveEffectiveDoctorId(SysUser request, SysUser existingUser, String effectiveRole) {
        if (!isDoctorLinkedRole(effectiveRole)) {
            return null;
        }
        // 显式传了就用传入值；没传则沿用数据库已有绑定，避免仅改昵称就把关联清空
        if (request.getDoctorId() != null) {
            return request.getDoctorId();
        }
        return existingUser.getDoctorId();
    }

    private Doctor validateDoctorBinding(String role, Long doctorId) {
        if (doctorId == null) {
            if (isDoctorLinkedRole(role)) {
                throw new IllegalArgumentException("医生与配药师账号必须关联有效档案");
            }
            return null;
        }
        if (!isDoctorLinkedRole(role)) {
            throw new IllegalArgumentException("只有医生或配药师账号可以关联医生档案");
        }
        if (doctorId < 1) {
            throw new IllegalArgumentException("医生ID必须为正整数");
        }
        Doctor doctor = doctorDao.selectByIdForUpdate(doctorId);
        if (doctor == null) {
            throw new IllegalArgumentException("关联医生不存在");
        }
        if (!role.equals(doctor.getRoleType())) {
            throw new IllegalArgumentException("账号角色必须与绑定的人员档案类型一致");
        }
        Department department = doctorDao.selectDepartmentForUpdate(doctorId);
        String expectedType = PHARMACIST_ROLE.equals(role) ? "PHARMACY" : "CLINICAL";
        if (department == null || !expectedType.equals(department.getType())) {
            throw new IllegalArgumentException("绑定人员类型必须与所属科室类型一致");
        }
        return doctor;
    }

    private void requireActiveDoctorDepartment(Doctor doctor) {
        Department department = doctorDao.selectEnabledDepartmentForUpdate(doctor.getId());
        if (department == null || !Integer.valueOf(1).equals(department.getStatus())) {
            throw new IllegalArgumentException("关联医生所属科室不存在或已停用");
        }
        String expectedType = PHARMACIST_ROLE.equals(doctor.getRoleType()) ? "PHARMACY" : "CLINICAL";
        if (!expectedType.equals(department.getType())) {
            throw new IllegalArgumentException("人员类型必须与所属科室类型一致");
        }
    }

    private void enableDoctorAfterAccountWrite(Doctor doctor) {
        if (Integer.valueOf(1).equals(doctor.getStatus())) {
            return;
        }
        Doctor update = new Doctor();
        update.setId(doctor.getId());
        update.setStatus(1);
        update.setUpdateTime(java.time.LocalDateTime.now());
        if (doctorDao.updateById(update) <= 0) {
            throw new IllegalStateException("启用关联医生失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int delete(Long id) {
        if (id == null || id < 1) {
            return 0;
        }
        List<Long> activeAdminIds = sysUserDao.lockActiveAdminIds();
        SysUser target = sysUserDao.getByIdForUpdate(id);
        if (target == null) {
            return 0;
        }
        if (activeAdminIds.size() == 1 && activeAdminIds.get(0).equals(id)) {
            throw new IllegalArgumentException(LAST_ACTIVE_ADMIN_MESSAGE);
        }
        revokeSessions(List.of(id));
        int affectedRows = sysUserDao.delete(id);
        if (affectedRows > 0 && target.getDoctorId() != null) {
            disableDoctorIfNoActiveAccounts(target.getDoctorId());
        }
        return affectedRows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("批量删除需提交至少一个用户ID");
        }
        if (ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw new IllegalArgumentException("用户ID必须为正整数");
        }
        List<Long> distinctIds = ids.stream().distinct().sorted().toList();
        List<Long> activeAdminIds = sysUserDao.lockActiveAdminIds();
        List<SysUser> targets = new java.util.ArrayList<>();
        for (Long id : distinctIds) {
            SysUser target = sysUserDao.getByIdForUpdate(id);
            if (target == null) {
                throw new IllegalArgumentException("部分用户不存在，批量删除已取消");
            }
            targets.add(target);
        }
        if (!activeAdminIds.isEmpty() && distinctIds.containsAll(activeAdminIds)) {
            throw new IllegalArgumentException(LAST_ACTIVE_ADMIN_MESSAGE);
        }
        revokeSessions(distinctIds);
        int affectedRows = sysUserDao.batchDelete(distinctIds);
        if (affectedRows != distinctIds.size()) {
            // 抛出运行时异常，确保存在缺失用户时已删除的行也回滚。
            throw new IllegalArgumentException("部分用户不存在，批量删除已取消");
        }
        targets.stream()
                .map(SysUser::getDoctorId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(this::disableDoctorIfNoActiveAccounts);
        return affectedRows;
    }

    private void disableDoctorIfNoActiveAccounts(Long doctorId) {
        Doctor current = doctorDao.selectByIdForUpdate(doctorId);
        if (current == null || Integer.valueOf(0).equals(current.getStatus())) {
            return;
        }
        List<SysUser> accounts = sysUserDao.selectDoctorAccountsForUpdate(doctorId);
        if (accounts != null && accounts.stream()
                .anyMatch(account -> Integer.valueOf(1).equals(account.getStatus()))) {
            return;
        }
        Doctor update = new Doctor();
        update.setId(doctorId);
        update.setStatus(0);
        update.setUpdateTime(java.time.LocalDateTime.now());
        if (doctorDao.updateById(update) != 1) {
            throw new IllegalStateException("停用关联医生失败，账号修改已撤销");
        }
    }

    private void revokeSessions(List<Long> ids) {
        var valueOperations = stringRedisTemplate.opsForValue();
        for (Long id : ids) {
            valueOperations.set(LoginSession.REVISION_PREFIX + id, UUID.randomUUID().toString());
        }
    }
}
