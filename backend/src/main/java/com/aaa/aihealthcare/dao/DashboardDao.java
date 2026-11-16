package com.aaa.aihealthcare.dao;

import com.aaa.aihealthcare.vo.NameValueVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据概览统计 SQL，均为只读聚合查询。
 */
public interface DashboardDao {

    /** 今日预约：今日就诊（schedule_date = 今天）且未取消的预约数 */
    long countTodayAppointments(@Param("today") LocalDate today);

    /** 累计预约数 */
    long countAppointments();

    /** 在职医生数 */
    long countActiveDoctors();

    /** 注册患者数（role=USER 且未逻辑删除） */
    long countPatients();

    /** 指定日期区间内每天的预约数，name 为 yyyy-MM-dd */
    List<NameValueVO> countAppointmentsByDay(@Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate);

    /** 预约状态分布，name 为状态码 */
    List<NameValueVO> countByStatus();

    /** 科室预约量排行 */
    List<NameValueVO> countByDepartment(@Param("limit") int limit);

    /** 医生评价星级分布，name 为星级 1~5 */
    List<NameValueVO> countByRating();
}
