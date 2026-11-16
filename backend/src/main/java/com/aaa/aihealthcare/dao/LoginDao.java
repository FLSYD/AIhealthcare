package com.aaa.aihealthcare.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.aaa.aihealthcare.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface LoginDao extends BaseMapper<SysUser> {

    SysUser getByUsername(@Param("username") String username);

    SysUser getById(@Param("id") Long id);
    java.util.List<String> selectAvatarReferences();

    int updateProfile(SysUser user);

    int updateAvatar(@Param("id") Long id, @Param("avatar") String avatar);

    int changePassword(@Param("id") Long id, @Param("oldHash") String oldHash,
            @Param("newHash") String newHash);
}
