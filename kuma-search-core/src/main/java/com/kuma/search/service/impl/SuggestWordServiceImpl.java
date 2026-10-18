package com.kuma.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.es.gateway.EsBulkGateway;
import com.kuma.search.es.gateway.EsIndexGateway;
import com.kuma.search.es.mapping.DocumentConverter;
import com.kuma.search.es.mapping.FieldConfig;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.repository.mapper.SearchContentMapper;
import com.kuma.search.service.IndexTypeConfigService;
import com.kuma.search.service.SuggestWordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 灌词的实现：内容 → 建议文档 → 联想索引。
 *
 * <h3>一条内容灌成什么样</h3>
 * 一个建议文档对应一条内容，{@code input} 是<b>多个</b>可被前缀命中的字符串：
 * <pre>
 *   {
 *     "suggest": { "input": ["中国传统器乐曲目-中国百科网",   ← 标题原文
 *                            "中国传统器乐曲目",             ← 标题主段（去掉「-站名」）
 *                            "zhongguochuantongqiyuequmuzhongguobaikewang",  ← 全拼
 *                            "zgctqyqmzgbkw"],               ← 首字母
 *                 "weight": 10 },
 *     "title": "中国传统器乐曲目-中国百科网",   ← 展示用，不建索引
 *     "bizId": "46482c8cab1368665df757416ace8c7f"
 *   }
 * </pre>
 *
 * <h3>为什么要有"标题主段"这一条</h3>
 * 采集来的标题几乎都是「正文标题-站点名」（{@code 中国传统器乐曲目-中国百科网}）。
 * 用户想找的是前半段，而 completion 是前缀匹配 —— 只灌完整标题的话，
 * 输入「中国传统器乐曲目」能命中（它是完整标题的前缀），
 * 但输入「中国传统」之后接着输「器乐曲目-中国」就断了。
 * 更典型的例子是「《百科论坛电子杂志》简介-中国百科网」：
 * 标题以书名号开头，用户<b>不可能</b>输入「《」去触发联想。
 * 因此把第一个有意义的分段也灌进去，让"从正题开头输入"能命中。
 *
 * <h3>拼音从哪来</h3>
 * 由 ES 节点的 {@code analysis-pinyin} 插件算（{@code _analyze}），不在 JVM 里算 ——
 * 理由见 {@code EsIndexGateway#analyzePinyinTokens}。
 * <b>代价要说清楚：一条内容多一次 ES 往返。</b>全量灌 5000 条约增加十几秒，
 * 对一个 6 小时跑一次的任务可以接受；换成 Java 拼音库能省掉这些往返，
 * 但会引入第二套拼音规则，得不偿失。
 *
 * <h3>上限与截断</h3>
 * <ul>
 *   <li>每个类型最多 {@code kuma.suggest.max-docs-per-type} 条，按 {@code biz_weight} 降序取；</li>
 *   <li>单条 input 截断到 {@link #MAX_INPUT_LENGTH}，与建议索引 mapping 的
 *       {@code max_input_length} 对齐 —— 比它长的 input 会被 ES <b>静默截断</b>，
 *       于是"输入完整拼音反而不出"，所以宁可自己先截。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestWordServiceImpl implements SuggestWordService {

    /**
     * 单条 input 的最大长度。
     *
     * <p>必须与 {@code MappingGenerator#buildSuggestIndexJson} 里的 {@code max_input_length}
     * 一致：这里是"我们主动截到多少"，那里是"ES 最多接受多少"。两个值不一致时，
     * 超出 ES 上限的部分会被静默丢掉，排查时看不到任何报错。
     */
    private static final int MAX_INPUT_LENGTH = 200;

    /** 一条内容最多灌几个 input。防的是"某类型配了好几个 suggest 字段"时 input 膨胀。 */
    private static final int MAX_INPUTS_PER_DOC = 8;

    /** 源表分页读取的页大小。与 {@code kuma.elasticsearch.bulk-batch-size} 同量级。 */
    private static final int SOURCE_PAGE_SIZE = 500;

    /** 建议文档里"展示用标题"的字段名，与 suggest 索引 mapping 及查询侧共用。 */
    private static final String FIELD_TITLE = "title";

    /** 建议文档的业务主键字段名，同时用作 ES 的 {@code _id}。 */
    private static final String FIELD_BIZ_ID = "bizId";

    /**
     * 标题分段用的分隔符。
     *
     * <p>把书名号、括号、竖线这些一起当分隔符，是为了让
     * 「《百科论坛电子杂志》简介-中国百科网」这种标题能被切成
     * {@code ["百科论坛电子杂志", "简介", "中国百科网"]}，取到第一段 "百科论坛电子杂志" ——
     * 它是用户真正会输入的东西。只按 {@code -} 切的话，第一段是「《百科论坛电子杂志》简介」，
     * 以书名号开头，前缀匹配等于没有。
     */
    private static final String TITLE_SEPARATORS = "[-–—_|·:：,，.。、/\\\\()（）《》〈〉<>【】\\[\\]「」『』\"'“”‘’!！?？~=+*&\\s]+";

    private final IndexTypeConfigService indexTypeConfigService;
    private final SearchContentMapper searchContentMapper;
    private final DocumentConverter documentConverter;
    private final EsIndexGateway esIndexGateway;
    private final EsBulkGateway esBulkGateway;
    private final KumaSearchProperties properties;

    @Override
    public int fill(String typeCode) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
        if (!def.hasSuggestField()) {
            // 与建索引、查询侧共用同一个开关：没有建议字段的类型连索引都不该有，
            // 这里也就没有东西可灌。
            log.debug("类型 {} 没有声明 suggest=true 的字段，跳过灌词", typeCode);
            return 0;
        }

        String indexName = def.suggestIndexName();
        int maxDocs = Math.max(1, properties.getSuggest().getMaxDocsPerType());

        List<Map<String, Object>> buffer = new ArrayList<>();
        int scanned = 0;
        int written = 0;
        int pageNum = 1;

        while (scanned < maxDocs) {
            Page<SearchContent> page = searchContentMapper.selectPage(
                    new Page<>(pageNum, SOURCE_PAGE_SIZE), publishedWrapper(typeCode));
            List<SearchContent> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }

            for (SearchContent content : records) {
                if (scanned >= maxDocs) {
                    break;
                }
                scanned++;

                Map<String, Object> document = documentConverter.toDocument(def, content);
                Map<String, Object> suggestDoc = buildSuggestDoc(def, content.getBizId(), document);
                if (suggestDoc == null) {
                    continue;
                }
                buffer.add(suggestDoc);

                if (buffer.size() >= SOURCE_PAGE_SIZE) {
                    written += flush(indexName, buffer);
                }
            }

            if (!page.hasNext()) {
                break;
            }
            pageNum++;
        }

        written += flush(indexName, buffer);

        // 灌完刷新一次，让"灌了多少条"这件事在 ES 侧立刻可见（日志与后台状态都读它）。
        // 这是 EsIndexGateway#refresh 允许的例外场景：一批全量写入结束后校验一次，
        // 而不是每次写入都刷。
        esIndexGateway.refresh(indexName);

        log.info("联想词灌入完成，type={}，index={}，扫描源数据={} 条，写入建议词={} 条，上限={}",
                typeCode, indexName, scanned, written, maxDocs);
        if (scanned >= maxDocs) {
            // 明确说出来：这是"被上限截住了"，不是"源数据只有这么多"。
            // 两者的现象一样（下拉框里搜不到较老的内容），但处置完全不同 ——
            // 前者要调 kuma.suggest.max-docs-per-type，后者要去看采集。
            log.warn("类型 {} 的内容数已达到联想词上限 {}，仅灌入了权重最高的部分。"
                            + "若希望更早/更全的内容也能被联想，请调大 kuma.suggest.max-docs-per-type",
                    typeCode, maxDocs);
        }
        return written;
    }

    @Override
    public void syncOne(String typeCode, String bizId, Map<String, Object> document) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
        if (!def.hasSuggestField()) {
            return;
        }

        Map<String, Object> suggestDoc = isPublished(document)
                ? buildSuggestDoc(def, bizId, document)
                : null;

        if (suggestDoc == null) {
            // 非发布状态（或内容里没有任何可用文本）→ 确保索引里没有它的建议词。
            // 删除是幂等的：不存在时什么都不发生。
            removeOne(typeCode, bizId);
            return;
        }

        esBulkGateway.upsert(def.suggestIndexName(), bizId, suggestDoc);
    }

    @Override
    public void removeOne(String typeCode, String bizId) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
        if (!def.hasSuggestField()) {
            return;
        }
        try {
            esBulkGateway.delete(def.suggestIndexName(), bizId);
        } catch (Exception e) {
            // 删除失败只告警：联想索引是<b>可重建的派生数据</b>，
            // 定时全量灌词会把状态纠正回来（见 SuggestRebuildJob）。
            // 这里抛出去会让"内容已下架"这个更重要的操作跟着失败，主次颠倒。
            log.warn("删除建议词失败，将在下次全量灌词时纠正: type={}，bizId={}，原因={}",
                    typeCode, bizId, e.getMessage());
        }
    }

    /**
     * 已发布内容的分页查询条件。
     *
     * <p>{@code orderByDesc(bizWeight).orderByAsc(id)}：前者决定"上限截断时留下谁"，
     * 后者是<b>分页稳定性的必需项</b> —— 没有唯一列参与排序时，
     * 数据库对相同 key 的行的返回顺序不做保证，翻页可能重复或漏掉记录。
     */
    private LambdaQueryWrapper<SearchContent> publishedWrapper(String typeCode) {
        return new LambdaQueryWrapper<SearchContent>()
                .eq(SearchContent::getTypeCode, typeCode)
                // 只灌已发布（status=1）。这是"未发布内容不得出现在下拉框里"的唯一防线。
                .eq(SearchContent::getStatus, 1)
                .orderByDesc(SearchContent::getBizWeight)
                .orderByAsc(SearchContent::getId);
    }

    /** 把缓冲区刷进 ES。返回本次成功写入的条数。 */
    private int flush(String indexName, List<Map<String, Object>> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        EsBulkGateway.BulkResult result = esBulkGateway.bulkUpsert(indexName, FIELD_BIZ_ID, List.copyOf(buffer));
        buffer.clear();
        if (result.hasFailure()) {
            log.warn("灌词部分失败，index={}，成功={}，失败={}，首条失败原因={}",
                    indexName, result.getSuccessCount(), result.getFailureCount(),
                    result.getFailures().isEmpty() ? "-" : result.getFailures().get(0));
        }
        return result.getSuccessCount();
    }

    /**
     * 组装一条建议文档。
     *
     * @return 没有任何可用文本时返回 null（调用方据此跳过或删除）
     */
    private Map<String, Object> buildSuggestDoc(IndexDefinition def, String bizId, Map<String, Object> document) {
        if (bizId == null || document == null || document.isEmpty()) {
            return null;
        }

        String indexName = def.suggestIndexName();
        Set<String> inputs = new LinkedHashSet<>();

        for (FieldConfig field : def.suggestFields()) {
            Object value = document.get(field.getName());
            if (value == null) {
                continue;
            }
            // multiValued 字段（如 tags）在文档里是数组：逐个取值，而不是把列表
            // 拼成 "[a, b]" 这种字符串 —— 那样灌进去的东西没有人会输入。
            if (value instanceof Collection<?> collection) {
                for (Object item : collection) {
                    collectInputs(indexName, String.valueOf(item), inputs);
                }
            } else {
                collectInputs(indexName, String.valueOf(value), inputs);
            }
        }

        if (inputs.isEmpty()) {
            return null;
        }

        return Map.of(
                "suggest", Map.of("input", List.copyOf(inputs), "weight", def.suggestWeight()),
                // 展示用标题：优先取文档里的 title（与主索引同名字段），
                // 取不到就退回第一条 input —— 下拉框宁可显示得笨一点，也不该空着。
                FIELD_TITLE, displayTitle(document, inputs),
                FIELD_BIZ_ID, bizId);
    }

    /**
     * 从<b>主索引文档</b>里取展示用标题。
     *
     * <p>用文档里的 {@code title} 而不是"建议字段的值"：类型可能把 suggest 开在别的字段上
     * （例如标签、分类名），那种情况下让下拉框显示标签是对的，但用户点进去想看到的是标题。
     * 拿不到 title 时退回第一条 input。
     */
    private String displayTitle(Map<String, Object> document, Set<String> inputs) {
        Object title = document.get(FIELD_TITLE);
        if (title != null && !String.valueOf(title).isBlank()) {
            return truncate(String.valueOf(title).trim());
        }
        return inputs.iterator().next();
    }

    /** 为一段文本收集全部 input：原文、主段、全拼、首字母。 */
    private void collectInputs(String indexName, String rawText, Set<String> inputs) {
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        String text = truncate(rawText.trim());
        if (text.isEmpty() || inputs.size() >= MAX_INPUTS_PER_DOC) {
            return;
        }
        inputs.add(text);

        String mainSegment = mainSegment(text);
        if (!mainSegment.isEmpty() && !mainSegment.equals(text) && inputs.size() < MAX_INPUTS_PER_DOC) {
            inputs.add(mainSegment);
        }

        if (inputs.size() >= MAX_INPUTS_PER_DOC) {
            return;
        }
        // 拼音按<b>整段</b>原文算（不按主段再算一次）：一次 ES 往返拿两个 token，
        // 而标题的拼音本身就是"整串"意义上的可联想文本。
        for (String pinyin : esIndexGateway.analyzePinyinTokens(indexName, text)) {
            if (inputs.size() >= MAX_INPUTS_PER_DOC) {
                break;
            }
            String token = truncate(pinyin);
            if (!token.isEmpty() && !token.equalsIgnoreCase(text)) {
                inputs.add(token);
            }
        }
    }

    /**
     * 标题的"主段"：第一个有意义的分段。
     *
     * <p>不足两个字符的分段会被跳过（分隔符相邻时会产生空段，
     * 而单字分段作为联想词几乎没有信息量，却会占掉一个 input 名额）。
     */
    private String mainSegment(String title) {
        for (String segment : title.split(TITLE_SEPARATORS)) {
            String trimmed = segment.trim();
            if (trimmed.length() >= 2) {
                return truncate(trimmed);
            }
        }
        return "";
    }

    /**
     * 内容是否处于"已发布"状态。
     *
     * <p>逐个类型判断而不是写死 {@code 1}：{@code t_search_content.status} 的语义
     * 是全局约定（0 待审 / 1 已发布 / 2 驳回），这里只是把它读出来。
     * 用宽松比较是因为事件里的 JSON 反序列化可能给出 Integer、Long 或 String。
     */
    private boolean isPublished(Map<String, Object> document) {
        if (document == null) {
            return false;
        }
        Object status = document.get("status");
        if (status instanceof Number number) {
            return number.intValue() == 1;
        }
        return "1".equals(String.valueOf(status));
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= MAX_INPUT_LENGTH ? trimmed : trimmed.substring(0, MAX_INPUT_LENGTH);
    }
}
