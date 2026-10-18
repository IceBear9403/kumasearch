package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 导入浏览器本地搜索历史的请求体，对应 {@code POST /api/auth/history/import}。
 *
 * <h3>为什么是"用户主动发起的一次导入"，而不是登录时自动合并</h3>
 * 未登录状态下存在浏览器里的搜索词，是<b>匿名行为</b>；登录之后这个账号是实名的。
 * 自动合并等于把两者静默关联起来，而用户对"没登录时搜的东西"有合理的不同预期 ——
 * 他当时的预期很可能正是"这不会留在我的账号上"。
 *
 * <p>因此前端在登录成功后弹一次「是否导入本地搜索记录？（导入后仅你可见）」，
 * 选是才调这个接口。这个决定属于用户，不属于实现。
 *
 * <h3>幂等性</h3>
 * 重复导入是安全的：服务端写入的是 ZSET，同一个关键词只会被覆盖 score（时间戳），
 * 不会产生重复条目。因此用户误点两次导入不会污染历史。
 */
@Data
@Schema(description = "导入浏览器本地搜索历史请求体")
public class HistoryImportRequest {

    /**
     * 类型编码，如 {@code article}、{@code webpage}。
     *
     * <p>必须显式传：历史在 Redis 里是<b>按类型分 key</b> 的
     * （{@code kuma:hist:{userId}:{typeCode}}），服务端无从猜测这一批词属于哪个类型。
     */
    @NotBlank(message = "typeCode 不能为空")
    @Size(max = 64, message = "typeCode 长度不能超过 64")
    @Schema(description = "类型编码，如 article、webpage", example = "article")
    private String typeCode;

    /**
     * 待导入的关键词列表。
     *
     * <p>上限 50 与 {@code HotWordServiceImpl.HISTORY_MAX_SIZE} 一致：
     * 服务端本来就只保留最近 50 条，允许传更多只会让超出部分被立刻裁掉 ——
     * 那是一次没有意义的写入。前端本地也只存 50 条，正常不会撞到这个上限，
     * 它拦的是"绕过前端直接调接口塞一万条"这种情况。
     *
     * <p>元素本身也不设单条长度校验：服务端在写入前会做归一化与截断，
     * 而为列表元素写 {@code @Size} 需要 {@code @Valid} 配合容器元素注解，
     * 在这里引入它只为省一次服务端判断，不划算。
     */
    @Size(max = 50, message = "一次最多导入 50 条")
    @Schema(description = "待导入的关键词列表，一次最多 50 条", example = "[\"spring boot\",\"elasticsearch\"]")
    private List<String> keywords;
}
