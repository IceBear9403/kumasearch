package com.kuma.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.constant.OutboxOpType;
import com.kuma.search.es.mapping.DocumentConverter;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.mq.IndexEvent;
import com.kuma.search.mq.IndexEventPublisher;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.repository.mapper.SearchContentMapper;
import com.kuma.search.service.ContentService;
import com.kuma.search.service.IndexTypeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 内容写入的实现 —— 本类是整个「写链路」的起点，也是最需要小心的地方。
 *
 * <h3>唯一必须守住的约定：数据与事件同事务</h3>
 * 每个写方法都在<b>同一个本地事务</b>里完成两件事：
 * 写 {@code t_search_content}，以及通过 {@link IndexEventPublisher} 写一条 Outbox 事件。
 * 这两件事分开提交就会产生一个无法自愈的窗口：
 * <ul>
 *   <li>先写业务表、后写事件 → 中间宕机则"数据在，搜索搜不到"，且没有任何机制会发现；</li>
 *   <li>先写事件、后写业务表 → 中间宕机则"搜索能搜到，点进去 404"，用户直接认为系统坏了。</li>
 * </ul>
 * 同事务之后，问题被收敛成"事件什么时候被消费"（可观测、可重试），
 * 而不是"事件会不会丢"（不可观测、只能靠对账发现）。
 *
 * <h3>关于"写入成功但搜不到"</h3>
 * 本类的返回只代表 MySQL 已持久化。ES 的写入由 Outbox 消费者异步完成，
 * 延迟由「消费者扫描间隔 + 索引 refresh_interval(1s)」决定。
 * 这个语义必须传达给调用方 —— 管理后台保存后应提示"已保存，索引同步中"，
 * 而不是立刻跳转到搜索结果页说"没找到"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentServiceImpl implements ContentService {

    private final SearchContentMapper searchContentMapper;
    private final IndexEventPublisher indexEventPublisher;
    private final IndexTypeConfigService indexTypeConfigService;
    private final DocumentConverter documentConverter;
    private final TransactionTemplate transactionTemplate;

    /** 批量导入时的分块大小。与 Outbox 消费的批大小保持一致。 */
    private static final int IMPORT_CHUNK_SIZE = 500;

    private static final int STATUS_ENABLED = 1;

    // ==================================================================================
    //  单条写入
    // ==================================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long save(SearchContent content) {
        validate(content);

        IndexDefinition def = indexTypeConfigService.resolveDefinition(content.getTypeCode());

        if (content.getStatus() == null) {
            content.setStatus(STATUS_ENABLED);
        }

        SearchContent existing = findWritable(content.getTypeCode(), content.getBizId());
        if (existing == null) {
            content.setId(null);
            try {
                searchContentMapper.insert(content);
            } catch (DuplicateKeyException e) {
                // 唯一键是 (type_code, biz_id)。并发写入同一业务主键时，
                // 后到的那个从"插入"变成"更新"，而不是把冲突抛给调用方 ——
                // 对上层而言这本来就是一个 upsert 语义。
                SearchContent concurrent = findExisting(content.getTypeCode(), content.getBizId());
                if (concurrent == null) {
                    throw BizException.of(ResultCode.DB_ERROR,
                            "内容写入冲突且无法定位已有记录: " + content.getBizId(), e);
                }
                content.setId(concurrent.getId());
                searchContentMapper.updateById(content);
            }
        } else {
            content.setId(existing.getId());
            searchContentMapper.updateById(content);
        }

        publishEvent(def, content, OutboxOpType.UPSERT);

        log.info("内容已保存并投递索引事件，type={}，bizId={}", content.getTypeCode(), content.getBizId());
        return content.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String typeCode, String bizId) {
        if (!StringUtils.hasText(typeCode) || !StringUtils.hasText(bizId)) {
            throw BizException.paramInvalid("typeCode 与 bizId 不能为空");
        }

        // 先解析类型定义作为守卫：对一个未配置或已停用的类型执行删除，
        // 说明调用方拿到了错误的 typeCode，此时静默返回成功会掩盖真正的问题。
        indexTypeConfigService.resolveDefinition(typeCode);

        SearchContent existing = findExisting(typeCode, bizId);
        if (existing == null) {
            // 删除一个不存在的记录按幂等处理：直接成功返回，不报 404。
            // 否则调用方在重试时会因为"上一次其实成功了"而拿到错误，徒增复杂度。
            log.debug("待删除的内容不存在，按幂等处理，type={}，bizId={}", typeCode, bizId);
            return;
        }

        // 逻辑删除：源数据保留，便于对账与回滚；ES 侧的文档则要真正删掉。
        searchContentMapper.deleteById(existing.getId());

        // DELETE 事件不带 payload —— 删除不需要文档内容，
        // 带上反而会在 Outbox 表里留下大量无用的大字段。
        indexEventPublisher.publish(IndexEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .typeCode(typeCode)
                .bizId(bizId)
                .opType(OutboxOpType.DELETE)
                .occurredAt(LocalDateTime.now())
                .build());

        log.info("内容已删除并投递索引删除事件，type={}，bizId={}", typeCode, bizId);
    }

    // ==================================================================================
    //  批量写入
    // ==================================================================================

    /**
     * 批量导入。
     *
     * <h3>为什么分块而不是一个大事务</h3>
     * 一个事务里塞一万次 insert 的代价不只是慢，而是<b>长事务</b>：
     * 它持有锁直到最后一行提交，期间任何并发的写入都要排队，
     * 且 undo log 会持续增长。更糟的是，第 9999 行失败会让前 9998 行一起回滚 ——
     * 用户看到的是一次十秒钟的等待后"全部失败"，完全不知道哪几条有问题。
     *
     * <p>分块后，每块是一个独立事务。某一块失败不影响已提交的块，
     * 且失败信息能精确到是哪一批数据。代价是「部分成功」这个中间状态 ——
     * 但对导入场景而言，能导入 9000 条并明确告诉你哪 1000 条失败，
     * 远好过全部失败后重来。
     *
     * @return 成功写入的条数
     */
    @Override
    public int batchSave(List<SearchContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        int success = 0;
        for (int start = 0; start < contents.size(); start += IMPORT_CHUNK_SIZE) {
            int end = Math.min(start + IMPORT_CHUNK_SIZE, contents.size());
            List<SearchContent> chunk = contents.subList(start, end);

            try {
                // 用一个独立事务包住整块：块内要么全成功，要么全回滚并整体重试。
                Integer done = transactionTemplate.execute(status -> saveChunk(chunk));
                success += done == null ? 0 : done;
            } catch (Exception e) {
                log.error("批量导入失败，区间=[{}, {})，原因={}", start, end, e.getMessage(), e);
                throw BizException.of(ResultCode.DB_ERROR,
                        "批量导入在第 " + (start + 1) + " 条附近失败（已成功 " + success + " 条）: "
                                + e.getMessage(), e);
            }
        }

        log.info("批量导入完成，总数={}，成功={}", contents.size(), success);
        return success;
    }

    private int saveChunk(List<SearchContent> chunk) {
        List<IndexEvent> events = new ArrayList<>(chunk.size());
        int saved = 0;

        for (SearchContent content : chunk) {
            try {
                validate(content);
                IndexDefinition def = indexTypeConfigService.resolveDefinition(content.getTypeCode());
                if (content.getStatus() == null) {
                    content.setStatus(STATUS_ENABLED);
                }

                SearchContent existing = findWritable(content.getTypeCode(), content.getBizId());
                if (existing == null) {
                    content.setId(null);
                    searchContentMapper.insert(content);
                } else {
                    content.setId(existing.getId());
                    searchContentMapper.updateById(content);
                }

                events.add(buildEvent(def, content, OutboxOpType.UPSERT));
                saved++;
            } catch (BizException e) {
                // 单条数据不合法不应该让整批失败：跳过它并记录，
                // 让调用方拿到"成功 N 条、失败 M 条"，而不是一个笼统的错误。
                log.warn("批量导入跳过一条非法数据，type={}，bizId={}，原因={}",
                        content.getTypeCode(), content.getBizId(), e.getMessage());
            }
        }

        indexEventPublisher.publishBatch(events);
        return saved;
    }

    @Override
    public SearchContent get(String typeCode, String bizId) {
        if (!StringUtils.hasText(typeCode) || !StringUtils.hasText(bizId)) {
            throw BizException.paramInvalid("typeCode 与 bizId 不能为空");
        }
        SearchContent content = findExisting(typeCode, bizId);
        if (content == null) {
            throw BizException.of(ResultCode.CONTENT_NOT_FOUND,
                    "内容不存在: " + typeCode + "/" + bizId);
        }
        return content;
    }

    // ==================================================================================
    //  内部实现
    // ==================================================================================

    private void validate(SearchContent content) {
        if (content == null) {
            throw BizException.paramInvalid("内容不能为空");
        }
        if (!StringUtils.hasText(content.getTypeCode())) {
            throw BizException.paramInvalid("typeCode 不能为空");
        }
        if (!StringUtils.hasText(content.getBizId())) {
            throw BizException.paramInvalid("bizId 不能为空。它会被用作 ES 文档 _id，缺失会导致重复文档");
        }
        // 标题是默认的检索主字段，没有标题的文档在结果列表里无法展示。
        if (!StringUtils.hasText(content.getTitle())) {
            throw BizException.paramInvalid("title 不能为空");
        }
    }

    private SearchContent findExisting(String typeCode, String bizId) {
        return searchContentMapper.selectOne(new LambdaQueryWrapper<SearchContent>()
                .eq(SearchContent::getTypeCode, typeCode)
                .eq(SearchContent::getBizId, bizId)
                .last("LIMIT 1"));
    }

    @Override
    public long countAliveByType(String typeCode) {
        if (!StringUtils.hasText(typeCode)) {
            return 0;
        }
        return searchContentMapper.countAliveByType(typeCode);
    }

    /**
     * 找出本次写入该落到哪一行：优先活行，其次**复活**一条被逻辑删除的同业务主键行。
     *
     * <h3>为什么不是简单地 findExisting</h3>
     * 唯一键 {@code uk_type_biz (type_code, biz_id)} 不含 {@code deleted}，而已删的行还占着这个位置。
     * 只查活行的话，「删了再写同一个 bizId」会走到：查不到 → INSERT → 撞唯一键 →
     * 兜底重新查活行还是查不到 → 抛出 {@code DB_ERROR「内容写入冲突且无法定位已有记录」}。
     * 那句话对使用者毫无意义，而这条路径是日常操作（采集重抓同一个 URL、投稿撤回后重投）。
     *
     * <p>复活必须发生在 INSERT 之前、且在同一个事务里 —— {@code save}/{@code saveChunk}
     * 都带 {@code @Transactional}，所以复活与随后的 UPDATE 要么都生效要么都不生效。
     * 若并发下两个请求同时复活，后一个的 UPDATE 匹配 0 行（已经是 deleted=0 了），
     * 它会当成「没有已删行」继续 insert —— 而 insert 会撞上活行的唯一键进入兜底分支，
     * 最终仍然是更新那一行。upsert 语义因此不破。
     */
    private SearchContent findWritable(String typeCode, String bizId) {
        SearchContent alive = findExisting(typeCode, bizId);
        if (alive != null) {
            return alive;
        }
        if (searchContentMapper.reviveDeleted(typeCode, bizId) > 0) {
            log.info("已复活一条被逻辑删除的内容行以承接本次写入，type={}，bizId={}", typeCode, bizId);
            return findExisting(typeCode, bizId);
        }
        return null;
    }

    /**
     * 构造 UPSERT 事件。
     */
    private IndexEvent buildEvent(IndexDefinition def, SearchContent content, OutboxOpType opType) {
        IndexEvent.IndexEventBuilder builder = IndexEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .typeCode(content.getTypeCode())
                .bizId(content.getBizId())
                .opType(opType)
                .occurredAt(LocalDateTime.now());

        if (opType == OutboxOpType.UPSERT) {
            // 快照在这里生成，而不是让消费者回查数据库 —— 见 IndexEvent 的类注释。
            builder.payload(documentConverter.toDocumentJson(def, content));
        }
        return builder.build();
    }

    private void publishEvent(IndexDefinition def, SearchContent content, OutboxOpType opType) {
        indexEventPublisher.publish(buildEvent(def, content, opType));
    }
}
