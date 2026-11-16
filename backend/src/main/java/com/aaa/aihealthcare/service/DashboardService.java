package com.aaa.aihealthcare.service;

import com.aaa.aihealthcare.vo.DashboardStatsVO;

public interface DashboardService {

    /**
     * 数据概览聚合统计：4 个 KPI + 4 组图表数据。
     */
    DashboardStatsVO stats();
}
