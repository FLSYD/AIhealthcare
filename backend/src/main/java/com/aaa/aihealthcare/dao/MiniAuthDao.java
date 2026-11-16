package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MiniAuthDao {
    SysUser selectByUsername(@Param("username") String username);

    SysUser selectByOpenidForUpdate(@Param("openid") String openid);

    int insertWechatUser(@Param("username") String username,
            @Param("passwordHash") String passwordHash,
            @Param("nickname") String nickname,
            @Param("openid") String openid);

    int updateAvatarForActiveUser(@Param("id") Long id, @Param("avatar") String avatar);
}
