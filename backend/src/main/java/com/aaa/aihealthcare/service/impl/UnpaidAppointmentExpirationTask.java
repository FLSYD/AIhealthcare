package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AppointmentPaymentWindow;
import com.aaa.aihealthcare.dao.AppointmentDao;
import com.aaa.aihealthcare.service.AppointmentService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 使用现有订单时间和取消事务回收未付款号源，不新增表或配置项。 */
@Component
@EnableScheduling
public class UnpaidAppointmentExpirationTask {
    private static final Logger log = LoggerFactory.getLogger(UnpaidAppointmentExpirationTask.class);
    private static final int BATCH_SIZE = 100;
    private final AppointmentDao appointmentDao;
    private final AppointmentService appointmentService;

    public UnpaidAppointmentExpirationTask(AppointmentDao appointmentDao, AppointmentService appointmentService) {
        this.appointmentDao = appointmentDao;
        this.appointmentService = appointmentService;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void closeExpiredAppointments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(AppointmentPaymentWindow.TIMEOUT_MINUTES);
        long afterId = 0L;
        // 按ID分批扫描；单条失败不影响同批其他订单，每轮最多处理1000条。
        for (int batch = 0; batch < 10; batch++) {
            List<Long> ids = appointmentDao.selectExpiredUnpaidIds(cutoff, afterId, BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                try {
                    appointmentService.expireUnpaid(id);
                } catch (RuntimeException exception) {
                    log.warn("自动关闭未付款预约失败，预约ID={}，下轮重试", id, exception);
                }
            }
            afterId = ids.get(ids.size() - 1);
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
    }
}
