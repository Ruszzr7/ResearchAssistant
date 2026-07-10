package com.research.assistant.service;

import com.research.assistant.dto.DashboardDto;

/**
 * 首页看板聚合服务。
 */
public interface DashboardService {

    /** 聚合看板数据 */
    DashboardDto aggregate();
}
