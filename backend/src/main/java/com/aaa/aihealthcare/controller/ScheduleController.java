package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.OperationLog;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.entity.Schedule;
import com.aaa.aihealthcare.service.ScheduleService;
import com.aaa.aihealthcare.vo.ScheduleBatchRequest;
import com.aaa.aihealthcare.vo.ScheduleVO;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequireRoles("ADMIN")
@RequestMapping("/api/schedule")
public class ScheduleController {

    @Autowired
    private ScheduleService scheduleService;

    @GetMapping("/page")
    public Result page(@RequestParam(required = false) Long doctorId,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                       @RequestParam(defaultValue = "1") Integer pageNum,
                       @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<ScheduleVO> pageInfo = scheduleService.page(doctorId, startDate, endDate, pageNum, pageSize);
        return Result.ok(pageInfo);
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        Schedule schedule = scheduleService.getById(id);
        if (schedule == null) {
            return Result.error("排班不存在");
        }
        return Result.ok(schedule);
    }

    @PostMapping
    @OperationLog(module = "排班管理", action = "新增排班")
    public Result save(@RequestBody Schedule schedule) {
        try {
            return scheduleService.save(schedule) ? Result.ok() : Result.error("新增失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 批量新增：日期 × 时段，已存在与过去日期自动跳过。 */
    @PostMapping("/batch")
    @OperationLog(module = "排班管理", action = "批量新增排班")
    public Result saveBatch(@RequestBody ScheduleBatchRequest request) {
        try {
            return Result.ok(scheduleService.saveBatch(request));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping
    @OperationLog(module = "排班管理", action = "修改排班")
    public Result update(@RequestBody Schedule schedule) {
        try {
            return scheduleService.update(schedule) ? Result.ok() : Result.error("修改失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @OperationLog(module = "排班管理", action = "删除排班")
    public Result deleteById(@PathVariable Long id) {
        try {
            return scheduleService.deleteById(id) ? Result.ok() : Result.error("删除失败");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/{id}/status/{status}")
    @OperationLog(module = "排班管理", action = "修改排班状态")
    public Result updateStatus(@PathVariable Long id, @PathVariable Integer status) {
        return scheduleService.updateStatus(id, status) ? Result.ok() : Result.error("状态修改失败");
    }
}