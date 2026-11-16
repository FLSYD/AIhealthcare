package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.Department;
import com.github.pagehelper.PageInfo;

public interface DepartmentService {

    PageInfo<Department> page(String keyword, Integer pageNum, Integer pageSize);

    Department getById(Long id);

    boolean save(Department department);

    boolean update(Department department);

    boolean deleteById(Long id);

    boolean updateStatus(Long id, Integer status);
}