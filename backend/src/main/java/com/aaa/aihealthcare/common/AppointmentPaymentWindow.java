package com.aaa.aihealthcare.common;

import java.time.LocalDateTime;

/** 挂号订单付款有效期，与新建预约的可预约时间窗口分开。 */
public final class AppointmentPaymentWindow {
    public static final int TIMEOUT_MINUTES = 15;

    private AppointmentPaymentWindow() {
    }

    public static boolean isExpired(LocalDateTime createTime, LocalDateTime now) {
        // 历史异常记录缺少创建时间时，不猜测到期时间并自动取消。
        return createTime != null && !createTime.plusMinutes(TIMEOUT_MINUTES).isAfter(now);
    }
}
