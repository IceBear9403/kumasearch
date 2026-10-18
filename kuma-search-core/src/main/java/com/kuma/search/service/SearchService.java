package com.kuma.search.service;

import com.kuma.search.dto.req.SearchRequestDTO;
import com.kuma.search.dto.resp.SearchResponseDTO;

/**
 * 检索服务 —— 对外的检索入口。
 *
 * <p>职责边界：本接口负责「参数校验 → 缓存 → 检索 → 结果加工」的编排，
 * 具体的 DSL 构造与 ES 交互委托给 {@code EsSearchGateway}。
 * 这样 Controller 只依赖本接口，将来把检索拆成独立服务时，改动只在本接口的实现里。
 */
public interface SearchService {

    /**
     * 执行检索。
     *
     * @param request  检索请求
     * @param userId   当前用户 ID，未登录时为 null。仅用于记录搜索历史与日志，不影响检索结果
     * @param clientIp 客户端 IP，用于日志与风控
     * @return 检索结果
     */
    SearchResponseDTO search(SearchRequestDTO request, Long userId, String clientIp);
}
