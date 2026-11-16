package com.aaa.aihealthcare.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.aaa.aihealthcare.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

@Mapper
public interface SysUserDao extends BaseMapper<SysUser> {

    SysUser getById(@Param("id") Long id);

    SysUser getByIdForUpdate(@Param("id") Long id);

    List<Long> lockActiveAdminIds();

    /**
     * 锁定医生名下所有未删除的医生账号。
     * 使用当前读，避免可重复读事务中的旧快照影响启停判断。
     */
    List<SysUser> selectDoctorAccountsForUpdate(@Param("doctorId") Long doctorId);
    
    SysUser selectByUsername(@Param("username") String username);
    
    int insert(SysUser user);
    
    int update(SysUser user);
    
    int delete(@Param("id") Long id);

    int batchDelete(@Param("ids") List<Long> ids);

    int updateDoctorAccountStatus(@Param("doctorId") Long doctorId,
                                  @Param("status") Integer status,
                                  @Param("updateTime") java.util.Date updateTime);
    
    List<SysUser> queryByCondition(@Param("sysUser") SysUser sysUser);
    
}
