package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.SysUser;
import com.github.pagehelper.PageInfo;
import java.util.List;

public interface SysUserService {

    PageInfo<SysUser> queryByCondition(Integer pageNum, Integer pageSize, SysUser sysUser);

    SysUser getById(Long id);

    SysUser selectByUsername(String username);

    int insert(SysUser user);

    int update(SysUser user);

    int delete(Long id);

    int batchDelete(List<Long> ids);
}
