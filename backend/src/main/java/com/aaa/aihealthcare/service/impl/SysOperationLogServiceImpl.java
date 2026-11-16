package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.common.UserValidation;
import com.aaa.aihealthcare.dao.SysOperationLogDao;
import com.aaa.aihealthcare.entity.SysOperationLog;
import com.aaa.aihealthcare.service.SysOperationLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SysOperationLogServiceImpl implements SysOperationLogService {

    private static final int MAX_BATCH_SIZE = 100;

    @Autowired
    private SysOperationLogDao sysOperationLogDao;

    @Override
    public PageInfo<SysOperationLog> page(String username, String module,
                                          Integer pageNum, Integer pageSize) {
        PageUtils.startPage(pageNum, pageSize);
        try {
            LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(username)) {
                wrapper.like(SysOperationLog::getUsername, username.trim());
            }
            if (StringUtils.hasText(module)) {
                wrapper.like(SysOperationLog::getModule, module.trim());
            }
            wrapper.orderByDesc(SysOperationLog::getCreateTime).orderByDesc(SysOperationLog::getId);

            List<SysOperationLog> list = sysOperationLogDao.selectList(wrapper);
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public SysOperationLog getById(Long id) {
        return sysOperationLogDao.selectById(id);
    }

    @Override
    public boolean save(SysOperationLog operationLog) {
        validate(operationLog);
        if (operationLog.getStatus() == null) {
            operationLog.setStatus(1);
        }
        if (operationLog.getCreateTime() == null) {
            operationLog.setCreateTime(LocalDateTime.now());
        }
        return sysOperationLogDao.insert(operationLog) > 0;
    }

    @Override
    public boolean record(String username, String module, String action, String method,
                          String url, String ip, Integer costMs, Integer status) {
        SysOperationLog operationLog = new SysOperationLog();
        operationLog.setUsername(username);
        operationLog.setModule(module);
        operationLog.setAction(action);
        operationLog.setMethod(method);
        operationLog.setUrl(url);
        operationLog.setIp(ip);
        operationLog.setCostMs(costMs);
        operationLog.setStatus(status);
        return save(operationLog);
    }

    @Override
    public boolean deleteById(Long id) {
        if (id == null || id < 1) {
            return false;
        }
        return sysOperationLogDao.deleteById(id) > 0;
    }

    @Override
    @Transactional
    public boolean deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("批量删除需提交1到100个日志ID");
        }
        if (ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw new IllegalArgumentException("日志ID必须为正整数");
        }
        List<Long> distinctIds = ids.stream().distinct().sorted().toList();
        Long existingCount = sysOperationLogDao.selectCount(
                new LambdaQueryWrapper<SysOperationLog>().in(SysOperationLog::getId, distinctIds));
        if (existingCount == null || existingCount != distinctIds.size()) {
            throw new IllegalArgumentException("部分日志不存在，批量删除已取消");
        }
        int affectedRows = sysOperationLogDao.deleteBatchIds(distinctIds);
        if (affectedRows != distinctIds.size()) {
            // 并发删除导致行数变化时一并回滚，避免只删除提交批次的一部分。
            throw new IllegalArgumentException("部分日志不存在，批量删除已取消");
        }
        return true;
    }

    private void validate(SysOperationLog operationLog) {
        if (operationLog == null) {
            throw new IllegalArgumentException("日志参数不能为空");
        }
        String username = UserValidation.normalizeUsername(operationLog.getUsername());
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("账号不能为空");
        }
        validateLength("账号", username, 50);
        validateLength("模块", operationLog.getModule(), 50);
        validateLength("操作", operationLog.getAction(), 50);
        validateLength("请求方法", operationLog.getMethod(), 10);
        validateLength("请求地址", operationLog.getUrl(), 200);
        validateLength("IP", operationLog.getIp(), 50);
        if (operationLog.getStatus() != null
                && operationLog.getStatus() != 0 && operationLog.getStatus() != 1) {
            throw new IllegalArgumentException("状态只能为0或1");
        }
        if (operationLog.getCostMs() != null && operationLog.getCostMs() < 0) {
            throw new IllegalArgumentException("耗时不能为负数");
        }
        operationLog.setUsername(username);
    }

    private void validateLength(String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + "不能超过" + maxLength + "个字符");
        }
    }
}
