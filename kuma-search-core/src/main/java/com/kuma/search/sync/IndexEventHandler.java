package com.kuma.search.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuma.search.cache.CacheKeys;
import com.kuma.search.cache.CacheService;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.constant.OutboxOpType;
import com.kuma.search.es.gateway.EsBulkGateway;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.mq.IndexEvent;
import com.kuma.search.service.IndexTypeConfigService;
import com.kuma.search.service.SuggestWordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 把一条索引事件落到 ES。
 *
 * <p>这是「事件」与「文档」之间的最后一跳，也是整条写链路唯一真正接触 ES 写接口的地方。
 * 无论底层是 Outbox 还是 RocketMQ，都会走到这里 —— 这正是把发布/消费抽成接口的收益：
 * 换中间件时本类一行都不用改。
 *
 * <h3>幂等性从哪来</h3>
 * 不靠去重表，靠 ES 本身：{@code _id} 用业务主键，UPSERT 走 index 操作（存在即覆盖），
 * DELETE 走 delete 操作（删不存在的文档不报错）。
 * 因此同一事件被投递十次的结果与一次完全相同。这是"至少一次投递"能安全工作的前提。
 *
 * <h3>它同时负责联想索引的增量灌词</h3>
 * 除了主索引，这里还会把内容同步进联想索引（{@code kuma_{typeCode}_suggest}）。
 * 放在这一层而不是各个业务入口，是因为本方法是所有写入的<b>唯一咽喉</b> ——
 * 详见 {@link #syncSuggestQuietly}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexEventHandler {

    private final IndexTypeConfigService indexTypeConfigService;
    private final EsBulkGateway esBulkGateway;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final SuggestWordService suggestWordService;

    /**
     * 处理一条事件。
     *
     * <p>写入目标是<b>物理索引名</b>而不是别名：reindex 过程中别名会切换，
     * 用别名会让"事件产生时还是旧索引、消费时已经是新索引"这类跨越发生，
     * 导致部分文档写进新索引、部分写进旧索引。物理索引名在事件处理开始时一次性确定，
     * 语义清晰：这条事件就是写给那个版本的。
     *
     * @throws BizException 处理失败时抛出，由调用方据此 nack 并按退避重试
     */
    public void handle(IndexEvent event) {
        if (event == null || !StringUtils.hasText(event.getTypeCode())) {
            log.warn("收到无效的索引事件，已忽略: {}", event);
            return;
        }

        IndexDefinition def = indexTypeConfigService.resolveDefinition(event.getTypeCode());
        String indexName = def.physicalIndexName();

        if (event.getOpType() == OutboxOpType.DELETE) {
            esBulkGateway.delete(indexName, event.getBizId());

            // 删除是唯一会主动清缓存的写入操作。
            // 理由：一条已删除的内容继续出现在搜索结果里，是用户能立刻看到的错误，
            // 甚至会让人以为系统"删不掉数据"；而新增内容晚几十秒出现则基本无感。
            // 删除在内容系统里的频率远低于新增/修改，清缓存的代价可以承受。
            evictSearchCache(event.getTypeCode());

            // 联想索引里的建议词也要一起删。这里用 <b>removeOne 而不是 syncOne</b>：
            // DELETE 事件不带文档内容，没有 status 可判断，语义是明确的"这条内容没了"。
            suggestWordService.removeOne(event.getTypeCode(), event.getBizId());

            log.info("已从索引删除文档，index={}，bizId={}", indexName, event.getBizId());
            return;
        }

        // ===== UPSERT =====
        Map<String, Object> document = parsePayload(event);
        if (document == null || document.isEmpty()) {
            // 空 payload 意味着事件本身是坏的（可能是历史脏数据）。
            // 抛异常比静默跳过好：静默跳过会让 ES 永远缺这条文档且无人知晓，
            // 而抛出去至少会走重试与死信，最终在监控里暴露出来。
            throw BizException.of(com.kuma.search.common.result.ResultCode.SYSTEM_ERROR,
                    "索引事件的 payload 为空或无法解析，eventId=" + event.getEventId());
        }

        esBulkGateway.upsert(indexName, event.getBizId(), document);

        // 刻意不在这里清检索缓存。原因是一个权衡，值得写清楚：
        // 内容写入是高频操作（批量导入时每秒数百次），若每次写入都清一遍按类型前缀的缓存，
        // 缓存将永远处于刚被清空的状态 —— 等于没有缓存，所有查询直接打到 ES。
        // 本项目的选择是：新增/修改的可见延迟上限 = 搜索结果缓存的 TTL，
        // 由 kuma.cache.search-ttl-seconds 控制（默认 60 秒）。删除路径则单独清缓存，见上。

        syncSuggestQuietly(event, document);

        log.debug("已写入索引文档，index={}，bizId={}", indexName, event.getBizId());
    }

    /**
     * 把这条内容同步到联想索引（增量灌词）。
     *
     * <h3>为什么挂在这里，而不是在发布/审核处各写一遍</h3>
     * 本方法是<b>所有内容写入 ES 的唯一咽喉</b>：发布、审核放行、驳回、后台录入、
     * 全量重建的灌数据，最终都汇到这一条路径上。把联想同步放在这里，
     * 意味着"将来新加一条写入链路"时它自动跟着生效 —— 而放在各个业务入口处，
     * 漏掉一个入口的表现是"这条内容搜得到、却联想不出来"，
     * 一个几乎没人会去验证的组合。
     *
     * <h3>失败为什么只告警，而主索引写入失败会抛异常</h3>
     * 两个索引的失败代价与修复手段不同：
     * <ul>
     *   <li>主索引缺文档 = 搜不到，且<b>只能靠人发现</b> —— 所以必须抛出去，走重试与死信；</li>
     *   <li>联想索引缺建议词 = 下拉框少一条，且定时全量灌词（{@code SuggestRebuildJob}）
     *       会把状态纠正回来。为一个可自愈的降级功能让主写入失败、进而回滚业务流程，是主次颠倒。</li>
     * </ul>
     * 因此这里吞掉异常，但把"会自愈"这件事写进日志 ——
     * 否则看到 WARN 的人会以为需要手工修。
     */
    private void syncSuggestQuietly(IndexEvent event, Map<String, Object> document) {
        try {
            suggestWordService.syncOne(event.getTypeCode(), event.getBizId(), document);
        } catch (Exception e) {
            log.warn("同步联想建议词失败（不影响检索，定时全量灌词会纠正）: type={}，bizId={}，原因={}",
                    event.getTypeCode(), event.getBizId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(IndexEvent event) {
        if (!StringUtils.hasText(event.getPayload())) {
            return null;
        }
        try {
            return objectMapper.readValue(event.getPayload(), Map.class);
        } catch (Exception e) {
            log.error("索引事件 payload 解析失败，eventId={}，type={}，bizId={}",
                    event.getEventId(), event.getTypeCode(), event.getBizId(), e);
            return null;
        }
    }

    private void evictSearchCache(String typeCode) {
        cacheService.evictByPrefix(CacheKeys.PREFIX + "search:" + typeCode + ":");
        cacheService.evictByPrefix(CacheKeys.PREFIX + "suggest:" + typeCode + ":");
    }
}
