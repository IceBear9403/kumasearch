package com.kuma.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.cache.CacheKeys;
import com.kuma.search.cache.CacheService;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.es.gateway.EsBulkGateway;
import com.kuma.search.es.gateway.EsIndexGateway;
import com.kuma.search.es.mapping.DocumentConverter;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.es.mapping.MappingGenerator;
import com.kuma.search.repository.entity.IndexTypeConfig;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.repository.mapper.SearchContentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引生命周期管理 —— 「配置驱动建索引」这条主链路的总编排。
 *
 * <h3>本类存在的意义</h3>
 * 没有它，每新增一个业务类型就要写一遍「建索引 + 建建议索引 + 灌数据 + 切别名」的代码。
 * 有了它，这些动作都由类型配置驱动，管理员在后台加一条配置即可。
 *
 * <h3>reindex 不停机的完整流程（{@link #rebuildIndex}）</h3>
 * <pre>
 *   1. 加分布式锁           —— 保证同一类型同时只有一个重建任务
 *   2. 建新版本物理索引      kuma_article_v2（<b>请求体里不带别名</b>，此时别名仍指向 v1，线上无感）
 *   3. 全量灌数据           从 MySQL 分批读、批量写 ES
 *   4. 刷新并校验           文档数比对（actual &lt; expected 即中止）  ← 这一步不能省
 *   5. 更新配置表版本号      current_version = 2   ← 必须早于第 6 步
 *   6. 原子切换别名          一个 update_aliases 请求完成 remove + add（失败则回滚版本号）
 *   6.5 补灌增量            重灌「重建开始后被改动过」的行   ← 见下方"两段写入窗口"
 *   7. 清理缓存             按前缀失效 + 广播，避免缓存里还是旧索引的结果
 *   8. 删除旧索引            kuma_article_v1
 *   9. 补齐并重灌联想索引    结构不对就重建，结构对也要重灌一次词
 * </pre>
 *
 * <p><b>第 4 步是这套流程里最容易被跳过、后果最严重的一步。</b>
 * 如果新索引因为分词器配置错误、字段缺失或数据源为空而只灌进了一半数据，
 * 直接切别名就等于把线上流量切到一个残缺的索引上 —— 表现为「索引重建后搜索结果少了一半」。
 * 恢复手段只有切回旧索引，而旧索引可能已经被删了。
 *
 * <h3>「写入打错索引」的两段窗口，各有各的机制兜着</h3>
 * 写入链路（{@code IndexEventHandler}）是拿<b>配置里的版本号</b>拼出物理索引名去写的，
 * 于是重建期间存在两段窗口，<b>它们不是同一个问题、也不能靠同一种办法解决</b>：
 * <ul>
 *   <li><b>步骤 3 期间（灌数据，最长的一段，10 万文档可达几十秒）</b>：配置里还是 v1，
 *       所以这期间的写入全部打在<b>旧索引</b>上。灌数据是按 id 升序分页读的，
 *       一条在"已经读过的页"里被改掉的内容，新索引里留下的就是旧版本 ——
 *       而步骤 8 删掉旧索引后，那份新版本就消失了。
 *       兜住它的是<b>第 6.5 步</b>：切完别名后按 {@code update_time} 把这段窗口重灌一遍。
 *       <br>⚠️ 这一步不能省，也不能指望对账：{@code ReconcileJob} 只做<b>存在性</b>检查，
 *       一份内容陈旧但存在的文档在它眼里是完全正常的。
 *   <li><b>步骤 5 到步骤 8 之间（改版本号之后、删旧索引之前）</b>：写入已经打到 newIndex，
 *       而读取仍走别名指向的旧索引，代价只是"刚写入的文档在别名切换前读不到"，
 *       延迟与 Outbox 的投递延迟同量级，没有数据会丢。这段窗口靠的就是
 *       <b>第 5 步排在第 6 步前面</b>：反过来（先切别名后改版本号）的话，
 *       这期间的写入会落在旧索引上，紧跟着被第 8 步删掉 ——
 *       而且写入方认为已经成功、不会重试，属于静默的数据丢失。
 *   若"改版本号"这一步本身失败（DB 抖动），别名指向 v2 而版本号长期停在 v1，
 *   之后所有写入会持续投向已被删除的索引且无法自愈，所以这一步与第 6 步必须紧邻。
 * </ul>
 * <p><b>仍未覆盖</b>：重建窗口内被<b>删除</b>的内容会残留在新索引里
 * （DELETE 事件同样打在旧索引上，而第 6.5 步只按"已发布的行"重灌、不会发现少了谁）。
 * 现状是等 {@code ReconcileJob} 报"ES 多了 N 条"或人工发现，登记在 {@code docs/08-roadmap.md}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicIndexManager {

    private final IndexTypeConfigService indexTypeConfigService;
    private final MappingGenerator mappingGenerator;
    private final EsIndexGateway esIndexGateway;
    private final EsBulkGateway esBulkGateway;
    private final SearchContentMapper searchContentMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheService cacheService;
    private final DocumentConverter documentConverter;
    private final SuggestWordService suggestWordService;
    private final KumaSearchProperties properties;

    /** 重建锁的持有时长。设得比预期重建时间宽裕一些，避免任务还在跑锁就过期了。 */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofMinutes(30);

    /** 灌数据时的批大小。 */
    private static final int POPULATE_BATCH_SIZE = 500;

    /**
     * 为一个类型创建索引（首次创建）。
     *
     * <p>同时创建主索引与联想索引，并建立别名。
     */
    public void createIndex(String typeCode) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);

        if (esIndexGateway.exists(def.physicalIndexName())) {
            throw BizException.of(ResultCode.INDEX_NOT_READY,
                    "物理索引已存在: " + def.physicalIndexName() + "，如需重建请使用 rebuildIndex");
        }

        String indexJson = mappingGenerator.buildIndexJson(def);
        esIndexGateway.createIndex(def.physicalIndexName(), indexJson);

        // 首次创建时旧索引为 null，只做 add
        esIndexGateway.switchAlias(def.getAlias(), def.physicalIndexName(), null);

        // 联想索引只在类型真的声明了 suggest 字段时才建。
        // 无条件建的话，"明确不要联想"的类型也会多出一个永远没人查的空索引 ——
        // 那正是"ES 里的索引集合"与"配置要求的索引集合"对不上的起点。
        createSuggestIndexAndFill(def);

        // 索引从无到有，此前可能被缓存下来的「空结果」必须一并作废。
        // 索引不存在时检索会降级成空结果（见 EsSearchGateway#emptyResult），
        // 那份空结果与「真的没搜到」在缓存里长得一模一样，会按 searchTtlSeconds 存活 60 秒。
        // 不在这里清的话，建完索引后的头一分钟里搜索仍然返回 0 条 ——
        // 恰好是「刚做完修复却发现没生效」这种最容易被误判成没修好的情形。
        evictSearchCache(typeCode);

        log.info("类型 {} 的索引创建完成: {} -> 别名 {}", typeCode, def.physicalIndexName(), def.getAlias());
    }

    /**
     * 全量重建索引（不停机）。
     *
     * <p>这是本类最重要的方法，流程见类注释。任何一步失败都不应影响当前正在服务的索引。
     */
    public void rebuildIndex(String typeCode) {
        String lockKey = CacheKeys.reindexLock(typeCode);
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "rebuilding", REBUILD_LOCK_TTL);

        if (!Boolean.TRUE.equals(locked)) {
            throw BizException.of(ResultCode.INDEX_NOT_READY,
                    "类型 " + typeCode + " 正在重建中，请勿重复触发");
        }

        try {
            IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
            String oldIndex = def.physicalIndexName();

            // 新版本号 = 当前 +1，对应新的物理索引
            int newVersion = def.getVersion() + 1;
            IndexDefinition newDef = IndexDefinition.builder()
                    .typeCode(def.getTypeCode())
                    .alias(def.getAlias())
                    .version(newVersion)
                    .shards(def.getShards())
                    .replicas(def.getReplicas())
                    .pkField(def.getPkField())
                    .fields(def.getFields())
                    .defaultSearchFields(def.getDefaultSearchFields())
                    .defaultHighlightFields(def.getDefaultHighlightFields())
                    .defaultSort(def.getDefaultSort())
                    .summaryFields(def.getSummaryFields())
                    .build();

            String newIndex = newDef.physicalIndexName();
            log.info("开始重建索引，type={}，{} -> {}", typeCode, oldIndex, newIndex);

            // 补灌窗口的起点。取 overlap 秒之前而不是"此刻"，是为了与增量同步的
            // overlap 口径一致：应用侧的 update_time 在跨实例时钟不一致时可能略早于真实写入时刻，
            // 少算这一小段就会漏掉边界上刚被改过的行。
            LocalDateTime rebuildStart = LocalDateTime.now()
                    .minusSeconds(properties.getSync().getOverlapSeconds());

            // 步骤 2：建新索引（别名不动，线上继续读旧索引）
            //
            // 【必须是 buildIndexJsonWithoutAlias】用 buildIndexJson 的话请求体里带着
            // aliases + is_write_index:true，而别名此刻还在 oldIndex 上 ——
            // ES 会以 "alias [x] has more than one write index" 拒绝整个建索引请求，
            // 于是本方法对任何已投产的类型都必然失败。详见该方法的注释。
            esIndexGateway.createIndex(newIndex, mappingGenerator.buildIndexJsonWithoutAlias(newDef));

            // 步骤 3：全量灌数据
            long populated = populate(newDef);

            // 步骤 4：刷新并校验 —— 不能省
            esIndexGateway.refresh(newIndex);
            long actual = esIndexGateway.countDocuments(newIndex);
            long expected = countSourceRows(typeCode);

            log.info("重建校验，type={}，期望={}，实际={}", typeCode, expected, actual);
            if (actual == 0 && expected > 0) {
                throw BizException.of(ResultCode.INDEX_NOT_READY,
                        "新索引 " + newIndex + " 为空但源数据有 " + expected + " 条，已中止切换");
            }
            if (actual < expected) {
                throw BizException.of(ResultCode.INDEX_NOT_READY,
                        "新索引文档数 " + actual + " 少于源数据 " + expected + " 条，已中止切换以避免搜索缺数据");
            }

            // 步骤 5：先把配置表的版本号推到新版本，**再**切别名。
            //
            // 【顺序为什么不能反】写入链路（IndexEventHandler）是按「配置里的版本号」
            // 拼出物理索引名去写的，也就是写 def.physicalIndexName()。若先切别名、后改版本号：
            //   - 从切别名到改版本号之间，任何一次内容写入仍然打在 oldIndex 上；
            //   - 紧接着步骤 8 会把 oldIndex 整个删掉 —— 这些刚写进去的文档随之消失，
            //     而且因为写入方认为"已经成功了"，不会有任何重试，是**静默的数据丢失**。
            // 更糟的是若"改版本号"这一步本身失败（DB 抖动），别名指向 v2 而版本号长期停在 v1，
            // 之后所有写入会持续投向已被删除的索引，且无法自愈。
            //
            // 反过来先改版本号：窗口期内的写入会落到 newIndex（已完成灌数据与校验），
            // 而读取仍走别名指向的 oldIndex —— 代价只是"刚写入的文档在别名切换前读不到"，
            // 延迟与 Outbox 的投递延迟同量级，且没有任何数据会丢。
            IndexTypeConfig config = indexTypeConfigService.getByTypeCode(typeCode);
            Integer previousVersion = config.getCurrentVersion();
            config.setCurrentVersion(newVersion);
            indexTypeConfigService.update(config);
            // 必须清掉配置缓存：否则本进程与其它实例在 TTL 内仍会用旧的 version 拼索引名，
            // 上面那段"写入打错索引"的问题依旧会发生。
            cacheService.evict(CacheKeys.indexTypeConfig(typeCode));

            // 步骤 6：原子切换别名
            try {
                esIndexGateway.switchAlias(def.getAlias(), newIndex, oldIndex);
            } catch (Exception e) {
                // 别名没切成，就把版本号回滚回去 —— 让配置与真实可读的索引重新对齐。
                // 回滚本身失败的话（极少见）会留下"配置说 v2、别名指向 v1"的状态，
                // 此时两个索引都存在、都不缺数据，靠 ReconcileJob 能发现；
                // 而如果不回滚，写入会持续打向一个下一步就要被删掉的索引，性质严重得多。
                rollbackVersion(typeCode, previousVersion);
                throw e;
            }

            // 步骤 6.5：补灌「重建开始后被改动过」的行。
            //
            // 【为什么必须有这一步】步骤 3 灌数据期间配置里的版本号还是 v1，
            // 于是这期间的所有写入都打在 oldIndex 上；而灌数据是按 id 升序分页读的，
            // 一条在"已经读过的页"里被改掉的内容，newIndex 里留下的是**旧版本** ——
            // 紧接着步骤 8 把 oldIndex 删掉，那份新版本就无影无踪了。
            // 步骤 5 之后的写入已经落在 newIndex 上（那是步骤 5 排在步骤 6 前面的收益），
            // 所以这里只需要补 [rebuildStart, 现在] 这一段，而且重灌是幂等的。
            //
            // 不能指望 ReconcileJob 兜这个：它做的是**存在性**检查，
            // 一份内容陈旧但存在的文档在它眼里完全正常。
            try {
                long repopulated = populateDelta(newDef, rebuildStart);
                if (repopulated > 0) {
                    log.info("重建期间被改动的内容已补灌 {} 条到新索引，type={}，窗口起点={}",
                            repopulated, typeCode, rebuildStart);
                }
                if (repopulated > POPULATE_BATCH_SIZE) {
                    // 超过一批说明重建期间有大量并发写入。补灌本身是对的，
                    // 但值得知道：这个类型的重建窗口与写入高峰重叠了，下次挑个更安静的时段。
                    log.warn("重建期间被改动的内容较多（{} 条），type={}；重建窗口与写入高峰重叠，"
                            + "建议下次避开该时段以减少补灌量", repopulated, typeCode);
                }
            } catch (Exception e) {
                // 补灌失败不影响这次重建的"可用性"结论（新索引已灌完、已校验、别名已切），
                // 但**必须让操作者知道**：漏掉的那几条会以旧内容的形式留在线上，
                // 而它不报错、搜得到，只有"内容明明改过了却没更新"这个现象。
                log.error("重建期间被改动的内容补灌失败，type={}，窗口起点={}；"
                                + "线上可能残留若干条旧版本内容，建议择机再重建一次: 原因={}",
                        typeCode, rebuildStart, e.getMessage(), e);
            }

            // 步骤 7：清缓存 —— 顺序在切别名之后，否则切换期间的新查询会把旧结果写进缓存
            evictSearchCache(typeCode);

            // 步骤 8：清理旧索引。放在最后，且失败不影响整体结果 ——
            // 留一个孤儿索引的代价远小于「切换失败」。
            try {
                esIndexGateway.deleteIndex(oldIndex);
            } catch (Exception e) {
                log.warn("删除旧索引失败，请手工清理: {}，原因={}", oldIndex, e.getMessage());
            }

            // 步骤 9：补齐索引集合里与本次重建无关的那些项 —— 目前就是联想索引。
            //
            // 它跟版本号无关、也不参与上面的别名切换，所以放在最后；失败只记 WARN 不抛：
            // 走到这里时新索引已经建好、数据已校验、别名已切换，不能因为一个联想索引
            // 把整次重建判为失败（那会误导操作者去"再重建一次"，而重建并不会修好它）。
            // 真正的修复责任在 IndexBootstrapJob：它每轮按索引集合做检查，这里失败它下一轮会补上。
            //
            // 【索引已存在时要额外灌一次词】createSuggestIndexAndFill 在索引已存在时直接返回
            // false 且不灌词 —— 那个语义是给 IndexBootstrapJob（每 10 分钟一轮）的：
            // 在那里无条件灌词等于周期性白干。但重建不一样：联想索引里的词是从主索引的内容
            // 派生出来的，主索引刚被重灌过，联想索引却可能还停在旧词上。
            // 更常见的一种是**索引建了、词从来没进去过**（灌词挂在写入成功之后，
            // 而写入失败时那一步根本走不到），此时重建主索引不会让联想自己好起来 ——
            // 现象是"内容搜得到、联想框里没有"，看起来像联想功能坏了。
            // 所以这里不用"是否新建"当条件，而是"结构齐了并且这个类型本来该有联想索引"就灌一次。
            try {
                if (createSuggestIndexAndFill(newDef)) {
                    log.info("重建时补齐了缺失的联想索引并完成灌词: {}", newDef.suggestIndexName());
                } else if (newDef.hasSuggestField()) {
                    int refilled = suggestWordService.fill(typeCode);
                    log.info("联想索引已存在，重建时重新灌词 {} 条: {}", refilled, newDef.suggestIndexName());
                }
            } catch (Exception e) {
                log.warn("补齐联想索引失败，联想功能暂不可用；自动建索引任务与定时全量灌词会在下一轮重试: {}，原因={}",
                        newDef.suggestIndexName(), e.getMessage());
            }

            log.info("索引重建完成，type={}，新索引={}，灌入文档数={}", typeCode, newIndex, populated);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }

    /**
     * 只重建联想索引。<b>删索引 → 建索引 → 全量灌词</b>三步，缺一不可。
     *
     * <h3>为什么是"删了重建"而不是"清空文档"</h3>
     * 分析器（{@code kuma_suggest_analyzer}、{@code kuma_suggest_pinyin}）与
     * {@code max_input_length} 这些都在索引的 settings 里，而 ES <b>不允许修改已有索引的
     * settings</b>（{@code PUT _mapping} 也改不了）。也就是说，一旦灌词规则变了
     * （就像本次从 ik 换成关键词分析器、加入拼音分析器），只有删了重建才能生效。
     * 用"清空文档再灌"的话，索引看着是好的、词也灌进去了，但分析器还是老的 ——
     * 表现为"改了配置却没有任何变化"，且没有任何报错。
     *
     * <p>代价说清楚：重建期间（删掉到灌完之间）联想是<b>空的</b>。
     * 这个窗口因此不能靠"频繁重建"来弥补新鲜度 —— 新增内容的可见性由
     * 发布时的增量灌词保证（{@code IndexEventHandler#syncSuggestQuietly}）。
     *
     * <p>对「配置里没有 suggest 字段」的类型是空操作：那种类型本就不该有联想索引，
     * 给它重建一个出来只会让 ES 里多一个永远没人查的索引。
     */
    public void rebuildSuggestIndex(String typeCode) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
        String suggestIndex = def.suggestIndexName();

        if (!def.hasSuggestField()) {
            log.warn("类型 {} 的 field_config 里没有字段声明 suggest=true，本就不该有联想索引 {}，"
                            + "本次不做任何事；若 ES 里确实存在这个索引，请手工删除",
                    typeCode, suggestIndex);
            return;
        }

        if (esIndexGateway.exists(suggestIndex)) {
            esIndexGateway.deleteIndex(suggestIndex);
        }
        createSuggestIndexIfAbsent(def);

        // 灌词放在索引建好之后、同一个方法里，而不是交给独立的定时任务去补：
        // 分开的话，"索引重建了但没人灌词"会成为一个稳定的中间状态
        // （定时任务要等到下个周期），而它在外表上与"灌完了但源数据没内容"一模一样 ——
        // 都是空索引，都不报错。
        try {
            int written = suggestWordService.fill(typeCode);
            log.info("联想索引 {} 已重建并灌词完成，写入 {} 条建议词", suggestIndex, written);
        } catch (Exception e) {
            // 索引结构已经是对的，只是没灌上词。抛出去会让调用方以为"重建失败"，
            // 而实际要做的事只是重跑一次灌词（或等定时全量）。
            log.error("联想索引 {} 已重建但灌词失败，联想功能暂不可用；"
                            + "定时全量灌词会在下一轮重试，也可手工再调一次本端点: 原因={}",
                    suggestIndex, e.getMessage(), e);
        }
    }

    /** 删除一个类型的全部索引与缓存。危险操作，需要管理员二次确认。 */
    public void deleteIndex(String typeCode) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);

        if (esIndexGateway.exists(def.physicalIndexName())) {
            esIndexGateway.deleteIndex(def.physicalIndexName());
        }
        if (esIndexGateway.exists(def.suggestIndexName())) {
            esIndexGateway.deleteIndex(def.suggestIndexName());
        }
        evictSearchCache(typeCode);
        log.warn("已删除类型 {} 的全部索引", typeCode);
    }

    /** 预演 mapping，供管理后台在保存配置前核对。 */
    public String previewMapping(String typeCode) {
        return mappingGenerator.previewMapping(indexTypeConfigService.resolveDefinition(typeCode));
    }

    /** 索引是否已就绪，供健康检查与搜索前置校验使用。 */
    public boolean exists(String typeCode) {
        try {
            IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
            return esIndexGateway.exists(def.physicalIndexName());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 索引状态汇总，供管理后台的索引列表页展示。
     *
     * <p><b>它同时是「ES 里的索引」与「配置要求的索引」之间唯一的对照点。</b>
     * 在本次修复之前这里只报主索引的 {@code exists} 与联想索引的<b>名字</b>，
     * 于是「主索引在、联想索引不在」这种半成品状态在这张表上是看不出来的 ——
     * 它只会在用户每次击键时以一条 {@code no such index} 的 WARN 露出一点边角。
     * 现在把「缺哪些」明确算出来（{@code missingIndexes}），让这类不匹配一眼可见，
     * 而不是只能靠联想功能出错来推断。
     */
    public Map<String, Object> indexStatus(String typeCode) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
        Map<String, Object> status = new HashMap<>();
        status.put("typeCode", typeCode);
        status.put("alias", def.getAlias());
        status.put("currentVersion", def.getVersion());
        status.put("physicalIndex", def.physicalIndexName());
        status.put("suggestIndex", def.suggestIndexName());

        boolean exists = esIndexGateway.exists(def.physicalIndexName());
        status.put("exists", exists);

        // 联想索引"该不该有"由配置决定（有没有字段声明 suggest=true），
        // 与"实际有没有"分开报 —— 两个布尔值合起来才能回答"这里正常吗"。
        boolean suggestRequired = def.hasSuggestField();
        boolean suggestExists = esIndexGateway.exists(def.suggestIndexName());
        status.put("suggestRequired", suggestRequired);
        status.put("suggestExists", suggestExists);

        List<String> missing = new ArrayList<>();
        if (!exists) {
            missing.add(def.physicalIndexName());
        }
        if (suggestRequired && !suggestExists) {
            missing.add(def.suggestIndexName());
        }
        status.put("missingIndexes", missing);

        status.put("documentCount", exists ? esIndexGateway.countDocuments(def.physicalIndexName()) : 0L);
        status.put("sourceRowCount", countSourceRows(typeCode));
        return status;
    }

    /** 重新加载同义词/停用词。注意只对之后写入的文档生效，历史文档需要重建索引。 */
    public void reloadAnalyzers(String typeCode) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
        esIndexGateway.reloadSearchAnalyzers(def.physicalIndexName());
    }

    // ==================================================================================
    //  内部实现
    // ==================================================================================

    /**
     * 确保某个类型的<b>索引集合</b>完整 —— 目前这个集合只有一项：联想索引。
     *
     * <p>它是 {@code IndexBootstrapJob} 修「主索引在、联想索引不在」这类半成品状态的入口。
     * 之所以需要这么一个入口，是因为<b>光看主索引看不出索引集合完整不完整</b>：
     * 主索引建好之后任何一步失败（历史上真实发生过：联想索引的建索引请求被 ES 以
     * 命名冲突拒掉，见 {@code MappingGenerator#buildSuggestIndexJson} 的说明），
     * 留下的都是同一个状态 —— 主索引齐活、联想索引缺席，而没有任何地方会去补它。
     *
     * @return 本次是否真的建了。false 表示「已经有了」或「这个类型本来就不需要」
     */
    public boolean ensureSuggestIndex(String typeCode) {
        return createSuggestIndexAndFill(indexTypeConfigService.resolveDefinition(typeCode));
    }

    /**
     * 建联想索引并灌词，是"索引集合补齐"的完整动作。
     *
     * <p><b>灌词为什么跟着建索引走</b>：一个刚建出来的联想索引是空的，
     * 而"空索引"与"没有内容可联想"在用户侧完全一样（下拉框都不出东西）。
     * 如果把灌词留给独立的定时任务，那么"索引补建好了"到"下个全量周期"之间
     * 会有一个默认长达 6 小时的空窗 —— 期间所有排查都会指向"是不是又没建成功"。
     *
     * @return 本次是否真的建了（没建就不用灌，避免重复劳动）
     */
    private boolean createSuggestIndexAndFill(IndexDefinition def) {
        if (!createSuggestIndexIfAbsent(def)) {
            return false;
        }
        try {
            int written = suggestWordService.fill(def.getTypeCode());
            log.info("联想索引 {} 已灌入 {} 条建议词", def.suggestIndexName(), written);
        } catch (Exception e) {
            // 索引结构是对的，只是还没词。这里不抛：调用方（建索引/重建/自动建索引）
            // 的主要目的已经达成，而灌词是可重试的 —— 定时全量灌词会补上。
            log.error("联想索引 {} 已创建但灌词失败，联想暂不可用；定时全量灌词会在下一轮重试: 原因={}",
                    def.suggestIndexName(), e.getMessage(), e);
        }
        return true;
    }

    /**
     * 建联想索引（若需要且不存在）。幂等。
     *
     * <p>不含灌词：{@link #rebuildSuggestIndex} 需要"先建、再由它自己灌"的顺序，
     * 用带灌词的版本会让它在同一次重建里灌两遍。
     *
     * @return 本次是否真的建了
     */
    private boolean createSuggestIndexIfAbsent(IndexDefinition def) {
        if (!def.hasSuggestField()) {
            // 配置里没有任何字段声明 suggest=true，那本类型就不该有联想索引，
            // 查询侧也不会去查它（见 EsSearchGateway#suggest）。
            // 返回 false 而不是抛异常：这不是错误，是配置表达的正常意图。
            return false;
        }
        if (esIndexGateway.exists(def.suggestIndexName())) {
            return false;
        }
        esIndexGateway.createIndex(def.suggestIndexName(), mappingGenerator.buildSuggestIndexJson(def));
        log.info("已创建联想索引: {}", def.suggestIndexName());
        return true;
    }

    /**
     * 把 MySQL 中的全量数据灌入新索引。
     *
     * <p>批量写入而不是逐条：逐条写入时每条都要一次网络往返，10 万条就是 10 万次，
     * 在 10ms 的往返延迟下需要将近 17 分钟。批量 500 条能把这个时间压到几十秒。
     *
     * <p>分页用 {@code id > lastId} 的 keyset，<b>不是</b> {@code OFFSET}。两个理由：
     * <ol>
     *   <li>OFFSET 在深分页时会退化成全表扫描（{@code LIMIT 500 OFFSET 95000} 要先把
     *       前 95000 行数过去），10 万行以后性能急剧下降；</li>
     *   <li>更关键的是<b>正确性</b>：灌数据期间源表是活的，OFFSET 分页按"第几行到第几行"取，
     *       而 keyset 按"id 大于多少"取 —— 后者的边界是不变量，不会因为并发写入而漂移。</li>
     * </ol>
     *
     * <p>与 {@link #populateDelta} 是同一套写法，只差一个时间下界：这段逻辑不该有两份实现。
     *
     * @return 成功写入的文档数
     */
    private long populate(IndexDefinition def) {
        long total = 0;
        long lastId = 0L;

        while (true) {
            List<SearchContent> records = searchContentMapper.selectList(new LambdaQueryWrapper<SearchContent>()
                    .eq(SearchContent::getTypeCode, def.getTypeCode())
                    .eq(SearchContent::getStatus, 1)
                    .gt(SearchContent::getId, lastId)
                    .orderByAsc(SearchContent::getId)
                    .last("LIMIT " + POPULATE_BATCH_SIZE));

            if (records == null || records.isEmpty()) {
                break;
            }

            List<Map<String, Object>> documents = records.stream()
                    .map(content -> documentConverter.toDocument(def, content))
                    .toList();

            EsBulkGateway.BulkResult bulkResult =
                    esBulkGateway.bulkUpsert(def.physicalIndexName(), def.getPkField(), documents);
            total += bulkResult.getSuccessCount();

            if (bulkResult.hasFailure()) {
                log.warn("重建过程中有文档写入失败，type={}，失败数={}，样例={}",
                        def.getTypeCode(), bulkResult.getFailureCount(),
                        bulkResult.getFailures().stream().limit(3).toList());
            }

            lastId = records.get(records.size() - 1).getId();
            if (records.size() < POPULATE_BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /**
     * 把「某个时刻之后被改动过」的行重灌进索引，用于补上重建窗口内的并发写入。
     *
     * <p>与 {@link #populate} 的区别只有一个：多一个 {@code update_time >= since} 的下界。
     * 其余（批量大小、转换、批量写、失败只告警）全部一致 —— 这段逻辑不该有两份实现。
     *
     * <p>分页用 {@code (update_time >= since) AND id > lastId} 按 id 升序做 keyset，
     * 而不是 OFFSET：偏移量分页在深分页时会退化成全表扫描，而且窗口内的行数
     * 本来就只跟"重建期间改了多少条"有关，keyset 的写法既正确又便宜。
     * 用 id 做游标是安全的 —— 这里的过滤集是"窗口内被改过的行"，
     * 按 id 升序每次取 {@code id > lastId} 能恰好把该集合走一遍，不多不少。
     *
     * <p>幂等：写的是完整的文档快照，重复投递等于覆盖，与 Outbox 那条路径不冲突。
     *
     * @return 成功写入的文档数
     */
    private long populateDelta(IndexDefinition def, LocalDateTime since) {
        long total = 0;
        long lastId = 0L;

        while (true) {
            List<SearchContent> rows = searchContentMapper.selectList(new LambdaQueryWrapper<SearchContent>()
                    .eq(SearchContent::getTypeCode, def.getTypeCode())
                    .eq(SearchContent::getStatus, 1)
                    .ge(SearchContent::getUpdateTime, since)
                    .gt(SearchContent::getId, lastId)
                    .orderByAsc(SearchContent::getId)
                    .last("LIMIT " + POPULATE_BATCH_SIZE));

            if (rows == null || rows.isEmpty()) {
                break;
            }

            List<Map<String, Object>> documents = rows.stream()
                    .map(content -> documentConverter.toDocument(def, content))
                    .toList();

            EsBulkGateway.BulkResult bulkResult =
                    esBulkGateway.bulkUpsert(def.physicalIndexName(), def.getPkField(), documents);
            total += bulkResult.getSuccessCount();

            if (bulkResult.hasFailure()) {
                log.warn("补灌过程中有文档写入失败，type={}，失败数={}，样例={}",
                        def.getTypeCode(), bulkResult.getFailureCount(),
                        bulkResult.getFailures().stream().limit(3).toList());
            }

            lastId = rows.get(rows.size() - 1).getId();
            if (rows.size() < POPULATE_BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /**
     * 统计源表行数（只算已发布的），用于重建前的校验，以及判断"新建的索引要不要接着灌数据"。
     *
     * <p>公开是因为 {@code IndexBootstrapJob} 要在建索引之前用它来决策：
     * 源表有数据却不灌，索引建好了也是个空壳，而"空索引"与"没有内容"在检索侧长得一模一样。
     */
    public long countSourceRows(String typeCode) {
        return searchContentMapper.selectCount(new LambdaQueryWrapper<SearchContent>()
                .eq(SearchContent::getTypeCode, typeCode)
                .eq(SearchContent::getStatus, 1));
    }

    /** 清空某类型的检索缓存。索引变了，缓存必须以它为 key 的那部分全部作废。 */
    private void evictSearchCache(String typeCode) {
        cacheService.evictByPrefix(CacheKeys.PREFIX + "search:" + typeCode + ":");
        cacheService.evictByPrefix(CacheKeys.PREFIX + "suggest:" + typeCode + ":");
    }

    /**
     * 别名切换失败时把版本号回滚到重建前的值。
     *
     * <p>回滚失败只告警不抛出：此时调用栈上已经有一个"切换失败"的异常在往上抛了，
     * 再抛一个会把真正的原因盖掉。留下"配置版本与实际别名不一致"的状态由
     * {@code ReconcileJob} 的报告能力去发现。
     */
    private void rollbackVersion(String typeCode, Integer previousVersion) {
        try {
            IndexTypeConfig config = indexTypeConfigService.getByTypeCode(typeCode);
            config.setCurrentVersion(previousVersion);
            indexTypeConfigService.update(config);
            cacheService.evict(CacheKeys.indexTypeConfig(typeCode));
            log.warn("别名切换失败，已把类型 {} 的版本号回滚到 {}", typeCode, previousVersion);
        } catch (Exception e) {
            log.error("回滚版本号失败！类型={}，期望回滚到={}，"
                            + "当前状态可能是「配置版本已更新但别名未切换」，请人工核对别名指向",
                    typeCode, previousVersion, e);
        }
    }
}
