package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.SysLoginLog;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface SysLoginLogService {

    PageInfo<SysLoginLog> page(String username, Integer pageNum, Integer pageSize);

    SysLoginLog getById(Long id);

    boolean save(SysLoginLog loginLog);

    boolean record(String username, String ip, Integer status, String message);

    boolean deleteById(Long id);

    boolean deleteBatch(List<Long> ids);
}