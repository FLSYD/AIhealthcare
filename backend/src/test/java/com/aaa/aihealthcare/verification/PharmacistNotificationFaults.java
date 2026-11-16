package com.aaa.aihealthcare.verification;

import com.aaa.aihealthcare.dao.NotificationDao;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 在新药师广播进入真实Mapper前开启既有SQL故障开关，不新增触发器或扩大验证账号权限。 */
final class PharmacistNotificationFaults {
    static NotificationDao armAtPharmacistInsert(NotificationDao real, JdbcTemplate jdbc) {
        NotificationDao gated=mock(NotificationDao.class,org.mockito.AdditionalAnswers.delegatesTo(real));
        doAnswer(call->{
            jdbc.update("INSERT INTO _verification_fault VALUES ('notification')");
            return real.insertForPharmacists(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3));
        }).when(gated).insertForPharmacists(anyLong(),anyString(),anyString(),anyString());
        return gated;
    }
    private PharmacistNotificationFaults() { }
}
