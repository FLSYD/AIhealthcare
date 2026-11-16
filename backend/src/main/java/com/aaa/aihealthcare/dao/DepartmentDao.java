package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.Department;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface DepartmentDao extends BaseMapper<Department> {

    Department selectByIdForUpdate(@Param("id") Long id);
}
