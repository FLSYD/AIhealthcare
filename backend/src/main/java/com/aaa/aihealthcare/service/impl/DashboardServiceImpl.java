package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.DashboardDao;
import com.aaa.aihealthcare.service.DashboardService;
import com.aaa.aihealthcare.vo.DashboardStatsVO;
import com.aaa.aihealthcare.vo.NameValueVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardServiceImpl implements DashboardService {

    /** 趋势图展示天数 */
    private static final int TREND_DAYS = 7;
    /** 科室排行取前 N 名 */
    private static final int DEPARTMENT_TOP_LIMIT = 8;
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");

    /** 预约状态中文文案，与计划书 5.4 节状态机保持一致 */
    private static final Map<String, String> STATUS_LABELS = new LinkedHashMap<>();

    static {
        STATUS_LABELS.put("unpaid", "待支付");
        STATUS_LABELS.put("pending", "待就诊");
        STATUS_LABELS.put("confirmed", "已确认");
        STATUS_LABELS.put("checked_in", "已到院");
        STATUS_LABELS.put("in_consultation", "就诊中");
        STATUS_LABELS.put("pending_rx_pay", "待缴药费");
        STATUS_LABELS.put("pending_pickup", "待取药");
        STATUS_LABELS.put("completed", "已完成");
        STATUS_LABELS.put("cancelled", "已取消");
    }

    @Autowired
    private DashboardDao dashboardDao;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardStatsVO stats() {
        LocalDate today = LocalDate.now();
        DashboardStatsVO vo = new DashboardStatsVO();

        vo.setTodayCount(dashboardDao.countTodayAppointments(today));
        vo.setTotalAppointments(dashboardDao.countAppointments());
        vo.setTotalDoctors(dashboardDao.countActiveDoctors());
        vo.setTotalUsers(dashboardDao.countPatients());

        vo.setAppointmentTrend(buildTrend(today));
        vo.setStatusDistribution(buildStatusDistribution());
        vo.setDepartmentTop(dashboardDao.countByDepartment(DEPARTMENT_TOP_LIMIT));
        vo.setRatingDistribution(buildRatingDistribution());
        return vo;
    }

    /**
     * 近 7 日趋势：数据库无数据的日期补 0，保证前端折线图 x 轴连续。
     */
    private List<NameValueVO> buildTrend(LocalDate today) {
        LocalDate start = today.minusDays(TREND_DAYS - 1L);
        List<NameValueVO> rows = dashboardDao.countAppointmentsByDay(start, today);

        Map<String, Long> counted = new LinkedHashMap<>();
        if (rows != null) {
            for (NameValueVO row : rows) {
                counted.put(row.getName(), row.getValue() == null ? 0L : row.getValue());
            }
        }

        List<NameValueVO> trend = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) {
            LocalDate day = start.plusDays(i);
            Long value = counted.get(day.format(DAY_KEY));
            trend.add(new NameValueVO(day.format(DAY_LABEL), value == null ? 0L : value));
        }
        return trend;
    }

    /**
     * 状态分布：按状态机顺序输出中文文案，数据库无记录的状态补 0，便于前端配色稳定。
     */
    private List<NameValueVO> buildStatusDistribution() {
        Map<String, Long> counted = new LinkedHashMap<>();
        List<NameValueVO> rows = dashboardDao.countByStatus();
        if (rows != null) {
            for (NameValueVO row : rows) {
                counted.put(row.getName(), row.getValue() == null ? 0L : row.getValue());
            }
        }

        List<NameValueVO> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : STATUS_LABELS.entrySet()) {
            Long value = counted.get(entry.getKey());
            result.add(new NameValueVO(entry.getValue(), value == null ? 0L : value));
        }
        // 数据库中出现了未登记的状态码时，也要如实展示
        for (Map.Entry<String, Long> entry : counted.entrySet()) {
            if (!STATUS_LABELS.containsKey(entry.getKey())) {
                result.add(new NameValueVO(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    /**
     * 星级分布：固定输出 1~5 星，无评价的星级补 0。
     */
    private List<NameValueVO> buildRatingDistribution() {
        Map<String, Long> counted = new LinkedHashMap<>();
        List<NameValueVO> rows = dashboardDao.countByRating();
        if (rows != null) {
            for (NameValueVO row : rows) {
                counted.put(row.getName(), row.getValue() == null ? 0L : row.getValue());
            }
        }

        List<NameValueVO> result = new ArrayList<>(5);
        for (int star = 1; star <= 5; star++) {
            String key = String.valueOf(star);
            Long value = counted.get(key);
            result.add(new NameValueVO(key + "星", value == null ? 0L : value));
        }
        return result;
    }
}
