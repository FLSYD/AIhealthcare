package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.common.DoctorUsername;
import com.aaa.aihealthcare.common.LoginSession;
import com.aaa.aihealthcare.dao.DepartmentDao;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.DoctorService;
import com.aaa.aihealthcare.vo.DoctorVO;
import com.aaa.aihealthcare.vo.DoctorCreateResultVO;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class DoctorServiceImpl implements DoctorService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    @org.springframework.beans.factory.annotation.Value("${healthcare.avatar-dir:uploads/avatars}")
    private String avatarDirectory;

    @Autowired
    private DoctorDao doctorDao;

    @Autowired
    private DepartmentDao departmentDao;

    @Autowired
    private SysUserDao sysUserDao;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public PageInfo<DoctorVO> page(String keyword, Long departmentId, Integer pageNum, Integer pageSize) {
        PageUtils.startPage(pageNum, pageSize);
        try {
            String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.strip() : null;
            List<DoctorVO> list = doctorDao.selectPageWithDepartment(normalizedKeyword, departmentId);
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public Doctor getById(Long id) {
        return doctorDao.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Doctor doctor) {
        if (doctor == null) {
            throw new IllegalArgumentException("医生信息不能为空");
        }
        sysUserDao.lockActiveAdminIds();
        Department department = lockDepartment(doctor.getDepartmentId());
        validate(doctor);
        requireStaffType(doctor, department);
        requireEnabledDepartment(department);
        doctor.setId(null);
        if (doctor.getGender() == null) {
            doctor.setGender(1);
        }
        if (doctor.getStatus() == null) {
            doctor.setStatus(1);
        }
        LocalDateTime now = LocalDateTime.now();
        doctor.setCreateTime(now);
        doctor.setUpdateTime(now);
        return doctorDao.insert(doctor) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DoctorCreateResultVO createWithAccount(Doctor doctor) {
        // 复用原档案校验和保存；外层事务同时覆盖档案、用户名分配及账号写入。
        if (!save(doctor) || doctor.getId() == null || doctor.getId() < 1) {
            throw new IllegalStateException("新增医生失败，登录账号未创建");
        }
        // 按所属科室类型决定人员类型与账号角色
        boolean pharmacy = isPharmacyDepartment(doctor.getDepartmentId());
        String personRole = pharmacy ? Doctor.ROLE_PHARMACIST : Doctor.ROLE_DOCTOR;
        String accountRole = pharmacy ? "PHARMACIST" : "DOCTOR";

        // 人员类型落库，供患者端与排班做隔离显示
        Doctor rolePatch = new Doctor();
        rolePatch.setId(doctor.getId());
        rolePatch.setRoleType(personRole);
        doctorDao.updateById(rolePatch);
        doctor.setRoleType(personRole);

        String baseUsername = DoctorUsername.baseName(doctor.getName());
        String username = availableUsername(baseUsername);
        SysUser account = new SysUser();
        account.setUsername(username);
        String initialPassword = "123";
        account.setPassword(passwordEncoder.encode(initialPassword));
        account.setNickname(doctor.getName());
        account.setRole(accountRole);
        account.setDoctorId(doctor.getId());
        account.setStatus(doctor.getStatus());
        Date accountTime = new Date();
        account.setCreateTime(accountTime);
        account.setUpdateTime(accountTime);
        // 医生档案由本事务刚刚创建，允许同时新增停用的档案和停用账号。
        try {
            if (sysUserDao.insert(account) != 1 || account.getId() == null || account.getId() < 1) {
                throw new IllegalStateException("登录账号创建失败，新增医生已撤销");
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("登录账号发生重名冲突，请重新提交", exception);
        }
        return new DoctorCreateResultVO(doctor.getId(), username, initialPassword);
    }

    /** 判断科室是否为配药科。 */
    private boolean isPharmacyDepartment(Long departmentId) {
        if (departmentId == null) {
            return false;
        }
        Department department = departmentDao.selectById(departmentId);
        return department != null && department.isPharmacy();
    }

    private String availableUsername(String baseUsername) {
        // 沿用启用管理员行锁串行分配用户名；已删除账号也保留名称，避免唯一键冲突。
        for (int sequence = 1; sequence <= 10_000; sequence++) {
            String suffix = sequence == 1 ? "" : Integer.toString(sequence);
            int prefixLength = Math.min(baseUsername.length(), 50 - suffix.length());
            String candidate = baseUsername.substring(0, prefixLength) + suffix;
            if (sysUserDao.selectByUsername(candidate) == null) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("该姓名对应的登录账号过多，请联系管理员处理");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(Doctor doctor) {
        if (doctor == null) {
            throw new IllegalArgumentException("医生信息不能为空");
        }
        requirePositiveId(doctor.getId());
        sysUserDao.lockActiveAdminIds();
        Doctor current = doctorDao.selectByIdForUpdate(doctor.getId());
        if (current == null) {
            throw new IllegalArgumentException("医生不存在");
        }
        Department department = lockDepartment(doctor.getDepartmentId());
        validate(doctor);
        if (doctor.getRoleType() == null || doctor.getRoleType().isBlank()) {
            doctor.setRoleType(current.getRoleType());
        }
        requireStaffType(doctor, department);
        if (!java.util.Objects.equals(current.getRoleType(), doctor.getRoleType())
                && (doctorDao.countUserReferencesByDoctorId(doctor.getId()) > 0
                || doctorDao.countSchedulesByDoctorId(doctor.getId()) > 0)) {
            throw new IllegalArgumentException("人员已关联账号或排班，不能直接变更人员类型");
        }
        Integer effectiveStatus = doctor.getStatus() == null ? current.getStatus() : doctor.getStatus();
        boolean enabling = Integer.valueOf(0).equals(current.getStatus())
                && Integer.valueOf(1).equals(effectiveStatus);
        boolean disabling = Integer.valueOf(0).equals(effectiveStatus);
        if (!disabling) {
            requireEnabledDepartment(department);
        }
        List<SysUser> doctorAccounts = List.of();
        if (enabling) {
            doctorAccounts = requireDoctorAccountsForEnable(doctor.getId());
            revokeDoctorSessions(doctorAccounts);
        } else if (disabling) {
            doctorAccounts = lockDoctorAccounts(doctor.getId());
            revokeDoctorSessions(doctorAccounts);
        }
        doctor.setCreateTime(null);
        doctor.setUpdateTime(LocalDateTime.now());
        if (doctorDao.updateById(doctor) <= 0) {
            return false;
        }
        if (disabling) {
            updateDoctorAccountStatus(doctor.getId(), 0, doctorAccounts);
        } else if (enabling) {
            updateDoctorAccountStatus(doctor.getId(), 1, doctorAccounts);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteById(Long id) {
        requirePositiveId(id);
        sysUserDao.lockActiveAdminIds();
        Doctor current = doctorDao.selectByIdForUpdate(id);
        if (current == null) {
            return false;
        }
        long scheduleCount = doctorDao.countSchedulesByDoctorId(id);
        if (scheduleCount > 0) {
            throw new IllegalArgumentException("该医生存在 " + scheduleCount + " 条排班记录，无法删除");
        }
        long userCount = doctorDao.countUserReferencesByDoctorId(id);
        if (userCount > 0) {
            throw new IllegalArgumentException("该医生仍被 " + userCount + " 个账号引用，无法删除");
        }
        return doctorDao.deleteById(id) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(Long id, Integer status) {
        requirePositiveId(id);
        validateStatus(status);
        sysUserDao.lockActiveAdminIds();
        Doctor current = doctorDao.selectByIdForUpdate(id);
        if (current == null) {
            return false;
        }
        boolean enabling = Integer.valueOf(0).equals(current.getStatus())
                && Integer.valueOf(1).equals(status);
        boolean disabling = Integer.valueOf(0).equals(status);
        List<SysUser> doctorAccounts = List.of();
        if (enabling) {
            Department department = lockDepartment(current.getDepartmentId());
            requireEnabledDepartment(department);
            doctorAccounts = requireDoctorAccountsForEnable(id);
            revokeDoctorSessions(doctorAccounts);
        } else if (disabling) {
            doctorAccounts = lockDoctorAccounts(id);
            revokeDoctorSessions(doctorAccounts);
        }
        Doctor doctor = new Doctor();
        doctor.setId(id);
        doctor.setStatus(status);
        doctor.setUpdateTime(LocalDateTime.now());
        if (doctorDao.updateById(doctor) <= 0) {
            return false;
        }
        if (disabling) {
            updateDoctorAccountStatus(id, 0, doctorAccounts);
        } else if (enabling) {
            updateDoctorAccountStatus(id, 1, doctorAccounts);
        }
        return true;
    }

    private List<SysUser> lockDoctorAccounts(Long doctorId) {
        List<SysUser> accounts = sysUserDao.selectDoctorAccountsForUpdate(doctorId);
        return accounts == null ? List.of() : accounts;
    }

    private List<SysUser> requireDoctorAccountsForEnable(Long doctorId) {
        List<SysUser> accounts = lockDoctorAccounts(doctorId);
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("医生没有未删除的绑定账号，无法启用");
        }
        return accounts;
    }

    private void revokeDoctorSessions(List<SysUser> accounts) {
        if (accounts.isEmpty()) {
            return;
        }
        var valueOperations = stringRedisTemplate.opsForValue();
        for (SysUser account : accounts) {
            valueOperations.set(LoginSession.REVISION_PREFIX + account.getId(),
                    UUID.randomUUID().toString());
        }
    }

    private void updateDoctorAccountStatus(Long doctorId, Integer status, List<SysUser> accounts) {
        long expected = accounts.stream().filter(account -> !status.equals(account.getStatus())).count();
        if (expected == 0) {
            return;
        }
        int affectedRows = sysUserDao.updateDoctorAccountStatus(doctorId, status, new Date());
        if (affectedRows != expected) {
            throw new IllegalStateException("医生账号状态同步失败");
        }
    }

    private Department lockDepartment(Long departmentId) {
        if (departmentId == null || departmentId < 1) {
            throw new IllegalArgumentException("所属科室不能为空");
        }
        Department department = departmentDao.selectByIdForUpdate(departmentId);
        if (department == null) {
            throw new IllegalArgumentException("所属科室不存在");
        }
        return department;
    }

    private void requireEnabledDepartment(Department department) {
        if (!Integer.valueOf(1).equals(department.getStatus())) {
            throw new IllegalArgumentException("所属科室已停用");
        }
    }

    private void requireStaffType(Doctor doctor, Department department) {
        String expected = department.isPharmacy() ? Doctor.ROLE_PHARMACIST : Doctor.ROLE_DOCTOR;
        if (doctor.getRoleType() == null || doctor.getRoleType().isBlank()) doctor.setRoleType(expected);
        if (!expected.equals(doctor.getRoleType())) {
            throw new IllegalArgumentException("人员类型必须与所属科室类型一致");
        }
    }

    private void validate(Doctor doctor) {
        doctor.setAvatar(com.aaa.aihealthcare.common.AvatarStorage.validateReference(doctor.getAvatar(), avatarDirectory));
        doctor.setName(requireTrimmed(doctor.getName(), 50, "医生姓名"));
        optionalLength(doctor.getTitle(), 50, "职称");
        optionalLength(doctor.getAvatar(), 255, "头像地址");
        optionalLength(doctor.getIntro(), 1000, "医生简介");
        if (doctor.getGender() != null && doctor.getGender() != 1 && doctor.getGender() != 2) {
            throw new IllegalArgumentException("性别值只能为 1 或 2");
        }
        if (doctor.getStatus() != null) {
            validateStatus(doctor.getStatus());
        }
    }

    private void validateStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("状态值只能为 0 或 1");
        }
    }

    private String requireTrimmed(String value, int maxLength, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String trimmed = value.strip();
        if (textLength(trimmed) > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private void optionalLength(String value, int maxLength, String label) {
        if (value != null && textLength(value) > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过 " + maxLength);
        }
    }

    private int textLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private void requirePositiveId(Long id) {
        if (id == null || id < 1) {
            throw new IllegalArgumentException("医生id必须为正整数");
        }
    }
}
