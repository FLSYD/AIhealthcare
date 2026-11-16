package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.vo.DoctorVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DoctorDao extends BaseMapper<Doctor> {

    List<DoctorVO> selectPageWithDepartment(@Param("keyword") String keyword,
                                           @Param("departmentId") Long departmentId);

    Doctor selectByIdForUpdate(@Param("id") Long id);

    Department selectEnabledDepartmentForUpdate(@Param("doctorId") Long doctorId);
    Department selectDepartmentForUpdate(@Param("doctorId") Long doctorId);

    int updateIntro(@Param("id") Long id, @Param("intro") String intro,
                    @Param("updateTime") LocalDateTime updateTime);

    int updateAvatar(@Param("id") Long id, @Param("avatar") String avatar,
                     @Param("updateTime") LocalDateTime updateTime);

    long countSchedulesByDoctorId(@Param("doctorId") Long doctorId);

    long countUserReferencesByDoctorId(@Param("doctorId") Long doctorId);
}
