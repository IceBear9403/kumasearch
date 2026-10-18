package com.kuma.search.service;

import com.kuma.search.dto.req.WebSearchRequestDTO;
import com.kuma.search.dto.resp.WebSearchResponseDTO;

/**
 * 网页采集内容的检索入口。
 *
 * <p>独立成接口而不是往 {@link SearchService} 上加一个方法：两者的<b>调用契约不同</b>。
 * {@code SearchService} 接受「任何类型 + 任意过滤条件」，是通用检索；
 * 这里只接受一个关键词与站点白名单，其余全部由服务端固定。
 * 把两者塞进同一个接口，等于让「哪些参数是调用方可控的」这件事失去结构上的表达。
 *
 * <p>实现<b>必须复用</b> {@link SearchService}，不得自己去调 ES。理由是复用带来的这一串
 * 现成能力都是必需的，任何一项自己重写都会立刻产生不一致：
 * <ul>
 *   <li>查询结果缓存（含缓存穿透的 null 占位）与失效广播；</li>
 *   <li>类型定义解析（{@code webpage} 走的是「配置驱动多类型」的既有机制）；</li>
 *   <li>字段白名单校验、高亮构造、字段裁剪；</li>
 *   <li>检索日志与热词记录。</li>
 * </ul>
 */
public interface WebSearchService {

    /**
     * 检索已审核通过的网页采集内容。
     *
     * @param request  请求。注意其中<b>没有</b> filters 字段，服务端会强制注入
     *                 {@code status=1}（已审核）过滤，调用方无法绕过，见
     *                 {@link WebSearchRequestDTO} 的类注释
     * @param userId   当前用户 ID，未登录时为 null。仅用于搜索历史与日志
     * @param clientIp 客户端 IP，仅用于日志与风控
     */
    WebSearchResponseDTO search(WebSearchRequestDTO request, Long userId, String clientIp);
}
