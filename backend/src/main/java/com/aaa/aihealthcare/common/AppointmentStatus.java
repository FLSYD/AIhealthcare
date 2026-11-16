package com.aaa.aihealthcare.common;

/**
 * 预约状态常量与流转规则，对应计划书 5.4 节状态机。
 */
public final class AppointmentStatus {

    /** 待支付 */
    public static final String UNPAID = "unpaid";
    /** 待就诊 */
    public static final String PENDING = "pending";
    /** 已确认 */
    public static final String CONFIRMED = "confirmed";
    /** 已到院 */
    public static final String CHECKED_IN = "checked_in";
    /** 就诊中 */
    public static final String IN_CONSULTATION = "in_consultation";
    /** 待缴药费 */
    public static final String PENDING_RX_PAY = "pending_rx_pay";
    /** 待取药 */
    public static final String PENDING_PICKUP = "pending_pickup";
    /** 已完成 */
    public static final String COMPLETED = "completed";
    /** 已取消 */
    public static final String CANCELLED = "cancelled";

    /** 处方状态 */
    public static final String RX_UNPAID = "unpaid";
    public static final String RX_PAID = "paid";
    public static final String RX_DISPENSED = "dispensed";
    public static final String RX_CANCELLED = "cancelled";

    private AppointmentStatus() {
    }

    /**
     * 是否允许标记完成：待就诊、已确认、就诊中。
     */
    public static boolean canComplete(String status) {
        return PENDING.equals(status) || CONFIRMED.equals(status) || IN_CONSULTATION.equals(status);
    }

    /**
     * 是否允许取消：待支付、待就诊、已确认、已到院。
     */
    public static boolean canCancel(String status) {
        return UNPAID.equals(status) || PENDING.equals(status)
                || CONFIRMED.equals(status) || CHECKED_IN.equals(status);
    }

    /**
     * 中文文案，供错误提示使用。
     */
    public static String label(String status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case UNPAID: return "待支付";
            case PENDING: return "待就诊";
            case CONFIRMED: return "已确认";
            case CHECKED_IN: return "已到院";
            case IN_CONSULTATION: return "就诊中";
            case PENDING_RX_PAY: return "待缴药费";
            case PENDING_PICKUP: return "待取药";
            case COMPLETED: return "已完成";
            case CANCELLED: return "已取消";
            default: return status;
        }
    }

    public static void validateFilter(String status) {
        if (status == null || status.isEmpty()) {
            return;
        }
        if (!java.util.Set.of(UNPAID, PENDING, CONFIRMED, CHECKED_IN, IN_CONSULTATION,
                PENDING_RX_PAY, PENDING_PICKUP, COMPLETED, CANCELLED).contains(status)) {
            throw new IllegalArgumentException("预约状态不正确");
        }
    }

    public static void validateDate(String date) {
        if (date == null || date.isEmpty()) {
            return;
        }
        try {
            java.time.LocalDate parsed = java.time.LocalDate.parse(date);
            if (!parsed.toString().equals(date)) {
                throw new java.time.format.DateTimeParseException("日期格式不正确", date, 0);
            }
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("就诊日期必须为有效的yyyy-MM-dd格式");
        }
    }
}
