package com.kuma.search.es.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.repository.entity.SearchContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MySQL 行 → ES 文档的转换器。
 *
 * <h3>为什么必须只有这一个实现</h3>
 * 这份转换存在两条调用路径：<b>全量重建</b>（{@code DynamicIndexManager} 分批灌数据）
 * 与<b>实时写入</b>（内容保存后立即写 ES）。如果两条路径各写一份转换逻辑，
 * 它们迟早会漂移 —— 表现为「刚发布的内容搜不到，但重建索引后就能搜到」，
 * 或者更糟：同一个字段在全量索引里是 {@code keyword}，在实时索引里成了字符串数组。
 * 这类 bug 极难定位，因为两条路径各自看都是对的。
 *
 * <h3>字段平铺规则</h3>
 * {@code t_search_content} 的固定列与 {@code extra} JSON 里的键会被<b>平铺到同一层</b>，
 * 因为 {@code field_config} 声明的是一份扁平字段清单。若让 extra 保持嵌套，
 * 配置里就得写成 {@code extra.brand}，而 ES 侧还要声明 nested 或 object 结构，
 * 徒增复杂度且与"配置驱动"的初衷相悖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentConverter {

    private final ObjectMapper objectMapper;

    /**
     * 转换为 ES 文档。
     *
     * <p>只输出 {@code field_config} 里声明过的字段。这样做不是为了减少体积，
     * 而是因为 mapping 里 {@code dynamic=false}：未声明的字段会被 ES 静默丢弃。
     * 在应用层提前裁剪，能让"哪些字段真的进了索引"这件事是确定的，
     * 而不是依赖 ES 的行为 —— 排查"为什么这个字段搜不到"时，这一点很关键。
     */
    public Map<String, Object> toDocument(IndexDefinition def, SearchContent content) {
        Map<String, Object> flat = flatten(def, content);

        Map<String, Object> filtered = new HashMap<>(flat.size());
        if (def.getFields() != null) {
            for (FieldConfig field : def.getFields()) {
                if (flat.containsKey(field.getName()) && flat.get(field.getName()) != null) {
                    filtered.put(field.getName(), flat.get(field.getName()));
                }
            }
        }
        return filtered;
    }

    /**
     * 转换为 ES 文档的 JSON 字符串，用于放进 Outbox 事件的 payload。
     *
     * <p>事件里存<b>完整快照</b>而不是只存 bizId，是为了消除消费时的竞态：
     * 若消费者在收到事件时回查数据库，读到的是"此刻"的值，
     * 而事件可能产生于这之前 —— 消费顺序与事件顺序不一致会导致 ES 里最终留下旧版本。
     */
    public String toDocumentJson(IndexDefinition def, SearchContent content) {
        try {
            return objectMapper.writeValueAsString(toDocument(def, content));
        } catch (Exception e) {
            log.error("文档序列化失败，type={}，bizId={}", def.getTypeCode(), content.getBizId(), e);
            throw BizException.of(ResultCode.SYSTEM_ERROR,
                    "文档序列化失败: " + content.getBizId(), e);
        }
    }

    /**
     * 把逗号分隔的标签串拆成数组。
     *
     * <h3>为什么必须拆</h3>
     * MySQL 里 tags 是一列 VARCHAR（"手机,数码,评测"），而 ES 侧这个字段被声明为
     * {@code keyword}。若原样写入一个字符串，ES 会把它当成<b>一个</b>词条索引 ——
     * 于是 tag 聚合出来的桶是「手机,数码,评测」这种整串，既不是分类维度也不是能点的筛选项。
     * 而按数组写入后，ES 会为每个元素各建一个 term，聚合与 term 过滤才符合直觉。
     *
     * <p>同时容忍中文逗号与顿号：后台录入时这几个符号是随手混用的，
     * 只认半角逗号会让「手机、数码」变成一条无法拆分的怪标签。
     *
     * @return 拆分后的标签列表；无标签时返回 {@code null}（而非空集合），
     *         让上层的非空过滤直接把该字段剔出文档，避免索引里留下空数组
     */
    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return null;
        }
        // LinkedHashSet：去重同时保留录入顺序，让聚合结果稳定可复现
        Set<String> result = new LinkedHashSet<>();
        for (String tag : tags.split("[,，、]")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? null : new ArrayList<>(result);
    }

    /**
     * 把实体平铺成一层 map。固定列优先，{@code extra} 中的同名字段不覆盖固定列。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flatten(IndexDefinition def, SearchContent content) {
        Map<String, Object> doc = new HashMap<>();

        doc.put("id", content.getBizId());
        doc.put("bizId", content.getBizId());
        // 主键必须落在配置声明的那一个字段名上：bulk 写入要用 doc.get(pkField) 作为 ES 的 _id，
        // 字段名对不上会导致文档 _id 缺失或退化成 ES 自动生成的随机 ID，upsert 幂等随即失效。
        if (def.getPkField() != null && !def.getPkField().isBlank()) {
            doc.put(def.getPkField(), content.getBizId());
        }
        doc.put("typeCode", content.getTypeCode());
        doc.put("title", content.getTitle());
        doc.put("content", content.getContent());
        doc.put("tags", splitTags(content.getTags()));
        doc.put("categoryId", content.getCategoryId());
        doc.put("authorId", content.getAuthorId());
        doc.put("price", content.getPrice());
        doc.put("bizWeight", content.getBizWeight());
        doc.put("status", content.getStatus());
        doc.put("publishTime", content.getPublishTime() == null
                ? null : content.getPublishTime().toString());

        if (content.getExtra() != null && !content.getExtra().isBlank()) {
            try {
                Map<String, Object> extra = objectMapper.readValue(content.getExtra(), Map.class);
                // putIfAbsent：extra 是管理员自由填写的，不能让它覆盖核心列。
                // 否则一个名叫 "title" 的额外字段能把标题覆盖成任意内容。
                extra.forEach(doc::putIfAbsent);
            } catch (Exception e) {
                // extra 是自由度最高的字段，解析失败不应该让整条内容写不进去。
                // 但必须留下明确日志，否则表现是"这个类型的扩展字段莫名其妙搜不到"。
                log.warn("内容 {}/{} 的 extra 字段不是合法 JSON，本次写入将忽略扩展字段，原因={}",
                        content.getTypeCode(), content.getBizId(), e.getMessage());
            }
        }

        return doc;
    }
}
