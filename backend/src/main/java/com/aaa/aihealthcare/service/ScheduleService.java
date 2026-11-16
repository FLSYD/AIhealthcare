package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.entity.Schedule;
import com.aaa.aihealthcare.vo.ScheduleBatchRequest;
import com.aaa.aihealthcare.vo.ScheduleBatchResultVO;
import com.aaa.aihealthcare.vo.ScheduleVO;
import com.github.pagehelper.PageInfo;

import java.time.LocalDate;

public interface ScheduleService {

    PageInfo<ScheduleVO> page(Long doctorId, LocalDate startDate, LocalDate endDate,
                              Integer pageNum, Integer pageSize);

    Schedule getById(Long id);

    boolean save(Schedule schedule);

    /** 批量新增：日期 × 时段，已存在的自动跳过；过去日期不创建。 */
    ScheduleBatchResultVO saveBatch(ScheduleBatchRequest request);

    boolean update(Schedule schedule);

    boolean deleteById(Long id);

    boolean updateStatus(Long id, Integer status);
}