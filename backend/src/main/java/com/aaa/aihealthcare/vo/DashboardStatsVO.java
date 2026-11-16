package com.aaa.aihealthcare.vo;

import lombok.Data;

import java.util.List;

/**
 * 数据概览聚合结果，对应计划书 6.3 节 GET /api/dashboard/stats 的返回结构。
 */
@Data
public class DashboardStatsVO {
    /** 今日预约（今日就诊的预约数） */
    private Long todayCount;
    /** 累计预约 */
    private Long totalAppointments;
    /** 在职医生（status=1） */
    private Long totalDoctors;
    /** 注册患者（role=USER 且未删除） */
    private Long totalUsers;
    /** 近 7 日预约趋势，name 为 MM-dd */
    private List<NameValueVO> appointmentTrend;
    /** 预约状态分布，name 为中文文案 */
    private List<NameValueVO> statusDistribution;
    /** 科室预约量 TOP，name 为科室名称 */
    private List<NameValueVO> departmentTop;
    /** 医生评价星级分布，name 为 1~5 星 */
    private List<NameValueVO> ratingDistribution;
}
