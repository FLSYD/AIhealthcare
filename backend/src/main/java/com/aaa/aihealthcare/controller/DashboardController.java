package com.aaa.aihealthcare.controller;

import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.common.RequireRoles;
import com.aaa.aihealthcare.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据概览接口，对应计划书 6.3 节。
 */
@RestController
@RequireRoles("ADMIN")
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboardService;

    @GetMapping("/stats")
    public Result stats() {
        return Result.ok(dashboardService.stats());
    }
}
