package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.PageUtils;
import com.aaa.aihealthcare.common.BookingTime;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.ScheduleDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.Schedule;
import com.aaa.aihealthcare.service.ScheduleService;
import com.aaa.aihealthcare.service.NotificationService;
import com.aaa.aihealthcare.vo.ScheduleBatchRequest;
import com.aaa.aihealthcare.vo.ScheduleBatchResultVO;
import com.aaa.aihealthcare.vo.ScheduleVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class ScheduleServiceImpl implements ScheduleService {

    @Autowired
    private ScheduleDao scheduleDao;

    @Autowired
    private DoctorDao doctorDao;

    @Autowired
    private SysUserDao sysUserDao;

    @Autowired
    private NotificationService notificationService;

    @Override
    public PageInfo<ScheduleVO> page(Long doctorId, LocalDate startDate, LocalDate endDate,
                                     Integer pageNum, Integer pageSize) {
        PageUtils.startPage(pageNum, pageSize);
        try {
            List<ScheduleVO> list = scheduleDao.selectPageWithDoctor(doctorId, startDate, endDate);
            return new PageInfo<>(list);
        } finally {
            PageHelper.clearPage();
        }
    }

    @Override
    public Schedule getById(Long id) {
        return scheduleDao.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Schedule schedule) {
        if (schedule == null) {
            throw new IllegalArgumentException("排班信息不能为空");
        }
        sysUserDao.lockActiveAdminIds();
        checkDoctorForUpdate(schedule.getDoctorId());
        validateRequired(schedule);
        requireFutureSlot(schedule);
        checkDuplicate(schedule.getDoctorId(), schedule.getScheduleDate(),
                schedule.getTimeSlot(), null);
        schedule.setId(null);
        schedule.setBookedCount(0);
        if (schedule.getStatus() == null) {
            schedule.setStatus(1);
        }
        LocalDateTime now = LocalDateTime.now();
        schedule.setCreateTime(now);
        schedule.setUpdateTime(now);
        try {
            boolean saved = scheduleDao.insert(schedule) > 0;
            if (saved) {
                notificationService.scheduleChanged(schedule.getDoctorId(), schedule.getId(), "您有新的排班，请查看我的排班。");
            }
            return saved;
        } catch (DuplicateKeyException exception) {
            throw duplicateSchedule(schedule, exception);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScheduleBatchResultVO saveBatch(ScheduleBatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("排班信息不能为空");
        }
        if (request.getDoctorId() == null || request.getDoctorId() < 1) {
            throw new IllegalArgumentException("请选择医生");
        }
        Set<String> dates = normalizeDates(request.getDates());
        Set<String> slots = normalizeSlots(request.getTimeSlots());
        Integer quota = request.getTotalQuota();
        if (quota == null || quota <= 0) {
            throw new IllegalArgumentException("总号源必须大于0");
        }
        Integer status = request.getStatus() == null ? 1 : request.getStatus();
        validateStatus(status);

        sysUserDao.lockActiveAdminIds();
        checkDoctorForUpdate(request.getDoctorId());

        ScheduleBatchResultVO result = new ScheduleBatchResultVO();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        for (String dateText : dates) {
            LocalDate date = LocalDate.parse(dateText);
            // 不创建过去日期的排班
            if (date.isBefore(today)) {
                result.setPastSkippedCount(result.getPastSkippedCount() + slots.size());
                result.getSkippedDetails().add(dateText + "（已过期）");
                continue;
            }
            for (String slot : slots) {
                if (date.equals(today) && !BookingTime.normalizeTime(slot).orElseThrow().isAfter(now.toLocalTime())) {
                    result.setPastSkippedCount(result.getPastSkippedCount() + 1);
                    result.getSkippedDetails().add(dateText + " " + slot + "（已过期）");
                    continue;
                }
                // 已存在的组合直接跳过，不中断整批创建
                if (existsSchedule(request.getDoctorId(), date, slot)) {
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    result.getSkippedDetails().add(dateText + " " + slot + "（已存在）");
                    continue;
                }
                Schedule schedule = new Schedule();
                schedule.setDoctorId(request.getDoctorId());
                schedule.setScheduleDate(date);
                schedule.setTimeSlot(slot);
                schedule.setTotalQuota(quota);
                schedule.setBookedCount(0);
                schedule.setStatus(status);
                schedule.setCreateTime(now);
                schedule.setUpdateTime(now);
                try {
                    if (scheduleDao.insert(schedule) > 0) {
                        result.setCreatedCount(result.getCreatedCount() + 1);
                        notificationService.scheduleChanged(request.getDoctorId(), schedule.getId(),
                                "您有新的排班，请查看我的排班。");
                    }
                } catch (DuplicateKeyException exception) {
                    // 并发下唯一键冲突也按跳过处理
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    result.getSkippedDetails().add(dateText + " " + slot + "（已存在）");
                }
            }
        }

        if (result.getCreatedCount() == 0 && result.getSkippedCount() == 0
                && result.getPastSkippedCount() == 0) {
            throw new IllegalArgumentException("没有可创建的排班");
        }
        return result;
    }

    /** 校验并去重日期列表，最多 5 天。 */
    private Set<String> normalizeDates(List<String> dates) {
        if (dates == null || dates.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个日期");
        }
        if (dates.size() > 5) {
            throw new IllegalArgumentException("一次最多创建 5 天的排班");
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String value : dates) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String text = value.strip();
            try {
                LocalDate.parse(text);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("日期格式必须是 yyyy-MM-dd：" + text);
            }
            parsed.add(text);
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个日期");
        }
        return parsed;
    }

    /** 校验并去重时段列表。 */
    private Set<String> normalizeSlots(List<String> timeSlots) {
        if (timeSlots == null || timeSlots.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个时段");
        }
        if (timeSlots.size() > 6) {
            throw new IllegalArgumentException("一次最多选择 6 个时段");
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String value : timeSlots) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String slot = value.strip();
            if (textLength(slot) > 20) {
                throw new IllegalArgumentException("时间段长度不能超过 20");
            }
            parsed.add(validTimeSlot(slot));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个时段");
        }
        return parsed;
    }

    private boolean existsSchedule(Long doctorId, LocalDate date, String timeSlot) {
        LambdaQueryWrapper<Schedule> wrapper = new LambdaQueryWrapper<Schedule>()
                .eq(Schedule::getDoctorId, doctorId)
                .eq(Schedule::getScheduleDate, date)
                .in(Schedule::getTimeSlot, equivalentSlots(timeSlot));
        return scheduleDao.selectCount(wrapper) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(Schedule schedule) {
        if (schedule == null) {
            throw new IllegalArgumentException("排班信息不能为空");
        }
        requirePositiveId(schedule.getId());
        sysUserDao.lockActiveAdminIds();
        List<Long> appointmentIds = scheduleDao
                .selectAppointmentIdsByScheduleIdForUpdate(schedule.getId());
        Schedule current = scheduleDao.selectByIdForUpdate(schedule.getId());
        if (current == null) {
            throw new IllegalArgumentException("排班不存在");
        }
        checkDoctorForUpdate(schedule.getDoctorId());
        validateRequired(schedule);
        // 历史排班可维护号源等非身份信息，新建或调整到已过期时段才拒绝。
        if (identityChanged(current, schedule)) requireFutureSlot(schedule);
        checkDuplicate(schedule.getDoctorId(), schedule.getScheduleDate(),
                schedule.getTimeSlot(), schedule.getId());

        int bookedCount = current.getBookedCount() == null ? 0 : current.getBookedCount();
        if (schedule.getBookedCount() != null && !Objects.equals(schedule.getBookedCount(), bookedCount)) {
            throw new IllegalArgumentException("已预约数由系统维护，不能直接修改");
        }
        if (schedule.getTotalQuota() < bookedCount) {
            throw new IllegalArgumentException("总号源不能小于当前已预约数 " + bookedCount);
        }

        if (!appointmentIds.isEmpty() && identityChanged(current, schedule)) {
            throw new IllegalArgumentException("该排班已有预约记录，不能修改医生、日期或时间段");
        }

        if (schedule.getStatus() == null) {
            schedule.setStatus(current.getStatus());
        }
        requireCanSuspend(current, schedule.getStatus());
        schedule.setBookedCount(null);
        schedule.setCreateTime(null);
        schedule.setUpdateTime(LocalDateTime.now());
        try {
            boolean updated = scheduleDao.updateWithoutBookedCount(schedule) > 0;
            if (updated) {
                if (!Objects.equals(current.getDoctorId(), schedule.getDoctorId())) {
                    notificationService.scheduleChanged(current.getDoctorId(), schedule.getId(), "您的排班已调整，请查看我的排班。");
                }
                notificationService.scheduleChanged(schedule.getDoctorId(), schedule.getId(), "您的排班已更新，请查看我的排班。");
            }
            return updated;
        } catch (DuplicateKeyException exception) {
            throw duplicateSchedule(schedule, exception);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteById(Long id) {
        requirePositiveId(id);
        sysUserDao.lockActiveAdminIds();
        List<Long> appointmentIds = scheduleDao.selectAppointmentIdsByScheduleIdForUpdate(id);
        Schedule current = scheduleDao.selectByIdForUpdate(id);
        if (current == null) {
            return false;
        }
        if (!appointmentIds.isEmpty()) {
            throw new IllegalArgumentException("该排班已有预约记录（含已取消或已完成），无法删除");
        }
        boolean deleted = scheduleDao.deleteById(id) > 0;
        if (deleted) {
            notificationService.scheduleChanged(current.getDoctorId(), id, "您的一条排班已删除，请查看我的排班。");
        }
        return deleted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(Long id, Integer status) {
        requirePositiveId(id);
        validateStatus(status);
        sysUserDao.lockActiveAdminIds();
        Schedule current = scheduleDao.selectByIdForUpdate(id);
        if (current == null) {
            return false;
        }
        if (Objects.equals(current.getStatus(), status)) {
            return true;
        }
        requireCanSuspend(current, status);
        Schedule schedule = new Schedule();
        schedule.setId(id);
        schedule.setStatus(status);
        schedule.setUpdateTime(LocalDateTime.now());
        boolean updated = scheduleDao.updateById(schedule) > 0;
        if (updated) {
            notificationService.scheduleChanged(current.getDoctorId(), id,
                    status == 1 ? "您的排班已启用，请查看我的排班。" : "您的排班已停用，请查看我的排班。");
        }
        return updated;
    }

    private Doctor checkDoctorForUpdate(Long doctorId) {
        if (doctorId == null || doctorId < 1) {
            throw new IllegalArgumentException("医生不能为空");
        }
        Doctor doctor = doctorDao.selectByIdForUpdate(doctorId);
        if (doctor == null) {
            throw new IllegalArgumentException("医生不存在");
        }
        if (!Integer.valueOf(1).equals(doctor.getStatus())) {
            throw new IllegalArgumentException("医生已停用，无法排班");
        }
        var department = doctorDao.selectEnabledDepartmentForUpdate(doctorId);
        if (!Doctor.ROLE_DOCTOR.equals(doctor.getRoleType()) || department == null
                || !"CLINICAL".equals(department.getType())) {
            throw new IllegalArgumentException("仅临床科室的医生可以排班");
        }
        return doctor;
    }

    private void validateRequired(Schedule schedule) {
        if (schedule.getScheduleDate() == null) {
            throw new IllegalArgumentException("排班日期不能为空");
        }
        if (!StringUtils.hasText(schedule.getTimeSlot())) {
            throw new IllegalArgumentException("时间段不能为空");
        }
        String timeSlot = schedule.getTimeSlot().strip();
        if (textLength(timeSlot) > 20) {
            throw new IllegalArgumentException("时间段长度不能超过 20");
        }
        schedule.setTimeSlot(validTimeSlot(timeSlot));
        if (schedule.getTotalQuota() == null || schedule.getTotalQuota() <= 0) {
            throw new IllegalArgumentException("总号源必须大于0");
        }
        if (schedule.getStatus() != null) {
            validateStatus(schedule.getStatus());
        }
    }

    private String validTimeSlot(String slot) {
        if (BookingTime.normalizeTime(slot).isEmpty()) {
            throw new IllegalArgumentException("时间段必须为严格的HH:mm、上午或下午");
        }
        return slot.strip();
    }

    private List<String> equivalentSlots(String slot) {
        var time = BookingTime.normalizeTime(slot).orElseThrow();
        return time.equals(java.time.LocalTime.of(9, 0)) ? List.of("上午", "09:00")
                : time.equals(java.time.LocalTime.of(14, 0)) ? List.of("下午", "14:00") : List.of(slot);
    }

    private void validateStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("状态值只能为 0 或 1");
        }
    }

    private boolean identityChanged(Schedule current, Schedule request) {
        return !Objects.equals(current.getDoctorId(), request.getDoctorId())
                || !Objects.equals(current.getScheduleDate(), request.getScheduleDate())
                || !Objects.equals(BookingTime.normalizeTime(current.getTimeSlot()),
                                  BookingTime.normalizeTime(request.getTimeSlot()));
    }

    private void requireFutureSlot(Schedule schedule) {
        LocalDateTime now = LocalDateTime.now();
        if (schedule.getScheduleDate().isBefore(now.toLocalDate())
                || (schedule.getScheduleDate().equals(now.toLocalDate())
                    && !BookingTime.normalizeTime(schedule.getTimeSlot()).orElseThrow().isAfter(now.toLocalTime()))) {
            throw new IllegalArgumentException("不能新增或调整到已过期的排班时段");
        }
    }

    private void requireCanSuspend(Schedule current, Integer targetStatus) {
        if (Integer.valueOf(0).equals(targetStatus) && !Integer.valueOf(0).equals(current.getStatus())
                && scheduleDao.countUnfinishedAppointments(current.getId()) > 0) {
            throw new IllegalArgumentException("该排班仍有未结束预约，请先处理预约后再停诊");
        }
    }

    private void checkDuplicate(Long doctorId, LocalDate scheduleDate, String timeSlot, Long excludeId) {
        LambdaQueryWrapper<Schedule> wrapper = new LambdaQueryWrapper<Schedule>()
                .eq(Schedule::getDoctorId, doctorId)
                .eq(Schedule::getScheduleDate, scheduleDate)
                .in(Schedule::getTimeSlot, equivalentSlots(timeSlot));
        if (excludeId != null) {
            wrapper.ne(Schedule::getId, excludeId);
        }
        if (scheduleDao.selectCount(wrapper) > 0) {
            throw new IllegalArgumentException("该医生在" + scheduleDate + "的" + timeSlot + "已有排班");
        }
    }

    private IllegalArgumentException duplicateSchedule(Schedule schedule, DuplicateKeyException cause) {
        return new IllegalArgumentException("该医生在" + schedule.getScheduleDate() + "的"
                + schedule.getTimeSlot() + "已有排班", cause);
    }

    private int textLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private void requirePositiveId(Long id) {
        if (id == null || id < 1) {
            throw new IllegalArgumentException("排班id必须为正整数");
        }
    }
}
