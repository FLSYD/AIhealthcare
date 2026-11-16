package com.aaa.aihealthcare.common;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * 患者预约共享的日期与时段规则。
 */
public final class BookingTime {
    private static final int BOOKING_DAYS = 7;
    private static final LocalTime MORNING = LocalTime.of(9, 0);
    private static final LocalTime AFTERNOON = LocalTime.of(14, 0);
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private BookingTime() {
    }

    /**
     * 判断日期和时段是否处于可预约窗口内。号源状态与余量由调用方另行校验。
     */
    public static boolean isBookable(LocalDate scheduleDate, String timeSlot) {
        return isBookable(scheduleDate, timeSlot, LocalDate.now(), LocalTime.now());
    }

    static boolean isBookable(LocalDate scheduleDate, String timeSlot,
                              LocalDate today, LocalTime now) {
        if (scheduleDate == null || today == null || now == null) {
            return false;
        }
        LocalDate lastBookableDate = today.plusDays(BOOKING_DAYS - 1L);
        if (scheduleDate.isBefore(today) || scheduleDate.isAfter(lastBookableDate)) {
            return false;
        }
        Optional<LocalTime> normalized = normalizeTime(timeSlot);
        if (normalized.isEmpty()) {
            return false;
        }
        return !scheduleDate.equals(today) || normalized.get().isAfter(now);
    }

    /**
     * 校验预约时间规则，不查询号源状态和余量。
     */
    public static void validateForBooking(LocalDate scheduleDate, String timeSlot) {
        validateScheduleDate(scheduleDate);
        if (normalizeTime(timeSlot).isEmpty()) {
            throw new IllegalArgumentException("预约时段必须为HH:mm、上午或下午");
        }
        if (!isBookable(scheduleDate, timeSlot)) {
            throw new IllegalArgumentException("该时段已过，不能预约");
        }
    }

    public static void validateScheduleDate(LocalDate scheduleDate) {
        if (scheduleDate == null) {
            throw new IllegalArgumentException("预约日期不能为空");
        }
        LocalDate today = LocalDate.now();
        if (scheduleDate.isBefore(today) || scheduleDate.isAfter(today.plusDays(BOOKING_DAYS - 1L))) {
            throw new IllegalArgumentException("预约日期必须在未来7天内");
        }
    }

    public static LocalDate parseScheduleDate(String scheduleDate) {
        if (scheduleDate == null || scheduleDate.isBlank()) {
            throw new IllegalArgumentException("预约日期不能为空");
        }
        try {
            LocalDate parsed = LocalDate.parse(scheduleDate);
            if (!parsed.toString().equals(scheduleDate)) {
                throw new DateTimeParseException("日期格式不正确", scheduleDate, 0);
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("预约日期必须为有效的yyyy-MM-dd格式");
        }
    }

    public static Optional<LocalTime> normalizeTime(String timeSlot) {
        if (timeSlot == null) {
            return Optional.empty();
        }
        String value = timeSlot.trim();
        if ("上午".equals(value)) {
            return Optional.of(MORNING);
        }
        if ("下午".equals(value)) {
            return Optional.of(AFTERNOON);
        }
        try {
            return Optional.of(LocalTime.parse(value, TIME_FORMATTER));
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }
}
