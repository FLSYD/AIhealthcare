package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.dao.DepartmentDao;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.service.DepartmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DepartmentServiceImpl implements DepartmentService {
    @Autowired
    private DepartmentDao departmentDao;

    @Autowired
    private DoctorDao doctorDao;

    @Autowired
    private SysUserDao sysUserDao;

    @Override
    public PageInfo<Department> page(String keyword, Integer pageNum, Integer pageSize) {
        PageUtils.startPage(pageNum, pageSize);
        try {
            LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(keyword)) {
                wrapper.like(Department::getName, keyword.trim());
            }
            wrapper.orderByAsc(Department::getSortOrder).orderByAsc(Department::getId);

            List<Department> list = departmentDao.selectList(wrapper);
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public Department getById(Long id) {
        return departmentDao.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Department department) {
        if (department == null) {
            throw new IllegalArgumentException("科室信息不能为空");
        }
        sysUserDao.lockActiveAdminIds();
        validate(department);
        if (nameExists(department.getName(), null)) {
            throw new IllegalArgumentException("科室名称已存在");
        }
        department.setId(null);
        if (department.getStatus() == null) {
            department.setStatus(1);
        }
        if (department.getSortOrder() == null) {
            department.setSortOrder(0);
        }
        LocalDateTime now = LocalDateTime.now();
        department.setCreateTime(now);
        department.setUpdateTime(now);
        try {
            return departmentDao.insert(department) > 0;
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("科室名称已存在", exception);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(Department department) {
        if (department == null) {
            throw new IllegalArgumentException("科室信息不能为空");
        }
        requirePositiveId(department.getId());
        sysUserDao.lockActiveAdminIds();
        Department current = departmentDao.selectByIdForUpdate(department.getId());
        if (current == null) {
            throw new IllegalArgumentException("科室不存在");
        }
        if (department.getType() == null || department.getType().isBlank()) {
            department.setType(current.getType());
        }
        validate(department);
        if (!java.util.Objects.equals(current.getType(), department.getType())) {
            Long staff = doctorDao.selectCount(new LambdaQueryWrapper<Doctor>().eq(Doctor::getDepartmentId, department.getId()));
            if (staff != null && staff > 0) {
                throw new IllegalArgumentException("该科室已有人员档案，不能直接变更科室类型");
            }
        }
        if (nameExists(department.getName(), department.getId())) {
            throw new IllegalArgumentException("科室名称已存在");
        }
        department.setCreateTime(null);
        department.setUpdateTime(LocalDateTime.now());
        try {
            return departmentDao.updateById(department) > 0;
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("科室名称已存在", exception);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteById(Long id) {
        requirePositiveId(id);
        sysUserDao.lockActiveAdminIds();
        Department current = departmentDao.selectByIdForUpdate(id);
        if (current == null) {
            return false;
        }
        Long doctorCount = doctorDao.selectCount(
                new LambdaQueryWrapper<Doctor>().eq(Doctor::getDepartmentId, id));
        if (doctorCount != null && doctorCount > 0) {
            throw new IllegalArgumentException("该科室下还有" + doctorCount + "名医生，无法删除");
        }
        return departmentDao.deleteById(id) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(Long id, Integer status) {
        requirePositiveId(id);
        validateStatus(status);
        sysUserDao.lockActiveAdminIds();
        if (departmentDao.selectByIdForUpdate(id) == null) {
            return false;
        }
        Department department = new Department();
        department.setId(id);
        department.setStatus(status);
        department.setUpdateTime(LocalDateTime.now());
        return departmentDao.updateById(department) > 0;
    }

    private void validate(Department department) {
        department.setName(requireTrimmed(department.getName(), 100, "科室名称"));
        // 科室类型：未传默认按临床科室处理
        String type = department.getType() == null ? "" : department.getType().strip();
        if (type.isEmpty()) {
            department.setType(Department.TYPE_CLINICAL);
        } else if (!Department.TYPE_CLINICAL.equals(type) && !Department.TYPE_PHARMACY.equals(type)) {
            throw new IllegalArgumentException("科室类型只能是 CLINICAL 或 PHARMACY");
        } else {
            department.setType(type);
        }
        optionalLength(department.getDescription(), 500, "科室描述");
        optionalLength(department.getLocation(), 200, "科室位置");
        if (department.getStatus() != null) {
            validateStatus(department.getStatus());
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
            throw new IllegalArgumentException("科室id必须为正整数");
        }
    }

    private boolean nameExists(String name, Long excludeId) {
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<Department>()
                .eq(Department::getName, name);
        if (excludeId != null) {
            wrapper.ne(Department::getId, excludeId);
        }
        return departmentDao.selectCount(wrapper) > 0;
    }
}
