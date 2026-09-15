package cn.chyuan.ai.domain.searchkernel.adapter.port;

import java.util.List;

/**
 * 搜索面端点契约描述（工单 0404 AW9）。
 * SearchController 端点清单（契约对账基线）：
 * GET/POST /api/v1/search/index、DELETE /api/v1/search/index/{docId}、GET /api/v1/search/query。
 * searchkernel.enabled 默认关，开启才注册；索引态为进程内演示实现（纯函数内核）。
 */
public interface SearchEndpointContract {

    /** 端点清单 */
    List<String> ENDPOINTS = List.of(
            "GET /api/v1/search/index",
            "POST /api/v1/search/index",
            "DELETE /api/v1/search/index/{docId}",
            "GET /api/v1/search/query");
}
