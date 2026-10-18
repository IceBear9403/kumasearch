package com.kuma.search.es.model;

import com.kuma.search.common.result.PageResult;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 检索链路的统一出参，是 {@code EsSearchGateway} 的返回类型。
 *
 * <p>与接口层的 {@code SearchResponseDTO} 分开：本类贴近检索语义（含 took、是否走了游标等），
 * DTO 则贴近前端契约（含权限裁剪后的字段等）。两者之间的映射在 {@code SearchServiceImpl} 里完成。
 */
@Data
@Builder
public class SearchResult implements Serializable {

    /** 命中列表。 */
    private List<EsHit> hits;

    /** 分页信息。游标模式下 {@code total} 为 null。 */
    private PageResult<EsHit> page;

    /** 各 facet 字段的聚合结果。未请求聚合时为空列表。 */
    private List<FacetResult> facets;

    /** ES 服务端执行耗时（毫秒），来自响应的 {@code took}。 */
    private Long tookMillis;

    /** 本次查询是否走了游标翻页。 */
    private boolean cursorMode;

    /** 服务端是否因超时/分片失败而返回了部分结果。前端应据此提示「结果可能不完整」。 */
    private boolean partial;
}
