package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.payment.NativePayment;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface NativePaymentDao {
    NativePayment find(@Param("id") Long id);
    NativePayment lock(@Param("id") Long id);
    NativePayment lockByMerchantOrderNo(@Param("orderNo") String orderNo);
    NativePayment lockByRequest(@Param("userId") Long userId, @Param("hash") String hash);
    NativePayment lockByActiveKey(@Param("key") String key);
    int insert(NativePayment payment);
    int update(NativePayment payment);
    int claimQuery(@Param("id") Long id);
    List<Long> selectPendingIds();
    List<Integer> selectReservedQuantities(@Param("medicineId") Long medicineId);
    int countActiveAppointment(@Param("appointmentId") Long appointmentId);
}
