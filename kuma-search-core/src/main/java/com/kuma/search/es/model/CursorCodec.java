package com.kuma.search.es.model;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * {@code search_after} 游标的编解码。
 *
 * <h3>为什么游标要自己编码而不是把 ES 的返回值直接丢给前端</h3>
 * <ol>
 *   <li>ES 的 {@code sort} 值数组是内部结构，直接暴露会让前端有机会篡改它，
 *       构造出非法的 {@code search_after} 请求；</li>
 *   <li>Base64 之后前端无法「顺手修改一个字段」，游标成为一个不透明的黑盒，
 *       这也是各类 API（GitHub、Stripe 等）统一采用的做法；</li>
 *   <li>将来若要在游标里附加额外信息（如过期时间、签名），不必改前端契约。</li>
 * </ol>
 *
 * <h3>已知限制</h3>
 * 只支持从排序值中还原出 ES 的标量类型（字符串 / 长整型 / 双精度 / 布尔 / null）。
 * 如果排序字段是复杂类型（如 geo_point、数组），游标会退化为「解码失败 → 从头开始翻页」，
 * 而不是返回错误 —— 因为翻页失败不应该让整个搜索不可用。
 * 对本项目的排序场景（主键、权重、价格、时间）来说，这个限制不影响功能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CursorCodec {

    private final ObjectMapper objectMapper;

    /**
     * 把 ES 返回的排序值编码成游标。
     *
     * @param sortValues 上一页最后一条命中的 {@code sort} 数组
     * @return Base64 编码的游标；sortValues 为空时返回 null（表示没有下一页）
     */
    public String encode(List<FieldValue> sortValues) {
        if (sortValues == null || sortValues.isEmpty()) {
            return null;
        }
        ArrayNode array = objectMapper.createArrayNode();
        for (FieldValue value : sortValues) {
            ObjectNode node = array.addObject();
            // 显式记录类型：JSON 数字无法区分 long 与 double，
            // 而 ES 的 search_after 对类型敏感（传 3 而不是 3.0 可能匹配不上）。
            if (value.isString()) {
                node.put("t", "s").put("v", value.stringValue());
            } else if (value.isLong()) {
                node.put("t", "l").put("v", value.longValue());
            } else if (value.isDouble()) {
                node.put("t", "d").put("v", value.doubleValue());
            } else if (value.isBoolean()) {
                node.put("t", "b").put("v", value.booleanValue());
            } else if (value.isNull()) {
                node.put("t", "n");
            } else {
                // 复杂类型：记为 json 字符串，解码时尽力还原
                node.put("t", "j").put("v", String.valueOf(value));
            }
        }
        String json = array.toString();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码游标。
     *
     * <p>解码失败时返回空列表而不是抛异常：游标的常见失效场景是「服务端改了排序规则」，
     * 此时最合理的处理是让用户从第一页重新开始，而不是弹一个错误框。
     *
     * @return 可直接用于 {@code search_after} 的排序值列表；无效游标返回空列表
     */
    public List<FieldValue> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return List.of();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            JsonNode array = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            if (!array.isArray()) {
                return List.of();
            }

            List<FieldValue> values = new ArrayList<>(array.size());
            for (JsonNode node : array) {
                String type = node.path("t").asText();
                JsonNode valueNode = node.path("v");
                switch (type) {
                    case "s" -> values.add(FieldValue.of(valueNode.asText()));
                    case "l" -> values.add(FieldValue.of(valueNode.asLong()));
                    case "d" -> values.add(FieldValue.of(valueNode.asDouble()));
                    case "b" -> values.add(FieldValue.of(valueNode.asBoolean()));
                    case "n" -> values.add(FieldValue.NULL);
                    default -> values.add(FieldValue.of(valueNode.asText()));
                }
            }
            return values;
        } catch (Exception e) {
            log.warn("游标解码失败，将从头开始翻页。cursor={}，原因={}",
                    cursor.length() > 32 ? cursor.substring(0, 32) + "..." : cursor, e.getMessage());
            return List.of();
        }
    }
}
