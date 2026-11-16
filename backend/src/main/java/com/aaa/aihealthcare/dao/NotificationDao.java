package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.vo.NotificationVO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface NotificationDao {
    List<NotificationVO> selectByUser(@Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly);
    long countUnread(@Param("userId") Long userId);
    boolean existsForUser(@Param("id") Long id, @Param("userId") Long userId);
    int markRead(@Param("id") Long id, @Param("userId") Long userId);
    int markAllRead(@Param("userId") Long userId);

    int insertForUser(@Param("userId") Long userId, @Param("type") String type,
                      @Param("title") String title, @Param("content") String content,
                      @Param("bizType") String bizType, @Param("bizId") Long bizId);
    int insertForDoctor(@Param("doctorId") Long doctorId, @Param("type") String type,
                        @Param("title") String title, @Param("content") String content,
                        @Param("bizType") String bizType, @Param("bizId") Long bizId);
    int insertForAppointmentDoctor(@Param("appointmentId") Long appointmentId,
                                   @Param("type") String type, @Param("title") String title,
                                   @Param("content") String content);
    int insertForPharmacists(@Param("prescriptionId") Long prescriptionId,
                             @Param("type") String type, @Param("title") String title,
                             @Param("content") String content);
}
