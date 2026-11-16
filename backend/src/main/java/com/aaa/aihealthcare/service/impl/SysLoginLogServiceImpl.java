package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.common.UserValidation;
import com.aaa.aihealthcare.dao.SysLoginLogDao;
import com.aaa.aihealthcare.entity.SysLoginLog;
import com.aaa.aihealthcare.service.SysLoginLogService;
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
public class SysLoginLogServiceImpl implements SysLoginLogService {

    private static final int MAX_BATCH_SIZE = 100;

    @Autowired
    private SysLoginLogDao sysLoginLogDao;

    @Override
    public PageInfo<SysLoginLog> page(String username, Integer pageNum, Integer pageSize) {
        PageUtils.startPage(pageNum, pageSize);
        try {
            LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(username)) {
                wrapper.like(SysLoginLog::getUsername, username.trim());
            }
            wrapper.orderByDesc(SysLoginLog::getCreateTime).orderByDesc(SysLoginLog::getId);

            List<SysLoginLog> list = sysLoginLogDao.selectList(wrapper);
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public SysLoginLog getById(Long id) {
        return sysLoginLogDao.selectById(id);
    }

    @Override
    public boolean save(SysLoginLog loginLog) {
        validate(loginLog);
        if (loginLog.getStatus() == null) {
            loginLog.setStatus(1);
        }
        if (loginLog.getCreateTime() == null) {
            loginLog.setCreateTime(LocalDateTime.now());
        }
        return sysLoginLogDao.insert(loginLog) > 0;
    }

    @Override
    public boolean record(String username, String ip, Integer status, String message) {
        SysLoginLog loginLog = new SysLoginLog();
        loginLog.setUsername(username);
        loginLog.setIp(ip);
        loginLog.setStatus(status);
        loginLog.setMessage(message);
        return save(loginLog);
    }

    @Override
    public boolean deleteById(Long id) {
        if (id == null || id < 1) {
            return false;
        }
        return sysLoginLogDao.deleteById(id) > 0;
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
        Long existingCount = sysLoginLogDao.selectCount(
                new LambdaQueryWrapper<SysLoginLog>().in(SysLoginLog::getId, distinctIds));
        if (existingCount == null || existingCount != distinctIds.size()) {
            throw new IllegalArgumentException("部分日志不存在，批量删除已取消");
        }
        int affectedRows = sysLoginLogDao.deleteBatchIds(distinctIds);
        if (affectedRows != distinctIds.size()) {
            throw new IllegalArgumentException("部分日志不存在，批量删除已取消");
        }
        return true;
    }

    private void validate(SysLoginLog loginLog) {
        if (loginLog == null) {
            throw new IllegalArgumentException("日志参数不能为空");
        }
        String username = UserValidation.normalizeUsername(loginLog.getUsername());
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("账号不能为空");
        }
        validateLength("账号", username, 50);
        validateLength("IP", loginLog.getIp(), 50);
        validateLength("说明", loginLog.getMessage(), 200);
        if (loginLog.getStatus() != null
                && loginLog.getStatus() != 0 && loginLog.getStatus() != 1) {
            throw new IllegalArgumentException("状态只能为0或1");
        }
        loginLog.setUsername(username);
    }

    private void validateLength(String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + "不能超过" + maxLength + "个字符");
        }
    }
}
