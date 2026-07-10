package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.DashboardDto;
import com.research.assistant.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页看板 REST 接口。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** GET /api/dashboard — 聚合看板数据 */
    @GetMapping
    public Result<DashboardDto> getDashboard() {
        return Result.ok(dashboardService.aggregate());
    }
}
