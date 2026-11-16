package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.SysOperationLog;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface SysOperationLogService {

    PageInfo<SysOperationLog> page(String username, String module, Integer pageNum, Integer pageSize);

    SysOperationLog getById(Long id);

    boolean save(SysOperationLog operationLog);

    boolean record(String username, String module, String action, String method,
                   String url, String ip, Integer costMs, Integer status);

    boolean deleteById(Long id);

    boolean deleteBatch(List<Long> ids);
}