package com.kuma.search.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果。
 *
 * <p>注意本项目的分页语义：搜索接口在超过浅分页上限后改用
 * {@code search_after} 游标翻页（见 {@code docs/05-core-flows.md}），
 * 此时 {@code total} 与 {@code pages} 可能为 {@code null}（ES 不再返回精确总数），
 * 前端应改用 {@code nextCursor} 判断是否还有下一页。
 *
 * @param <T> 列表元素类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    /** 当前页数据。 */
    private List<T> records;

    /** 总命中数。深分页模式或 {@code trackTotalHits=false} 时为 null。 */
    private Long total;

    /** 当前页码，从 1 开始。游标翻页模式下为 null。 */
    private Integer pageNum;

    /** 每页大小。 */
    private Integer pageSize;

    /** 总页数。total 为 null 时为 null。 */
    private Integer pages;

    /** 游标翻页模式下的下一页游标；为 null 表示已到末页。 */
    private String nextCursor;

    public static <T> PageResult<T> empty(int pageNum, int pageSize) {
        return new PageResult<>(Collections.emptyList(), 0L, pageNum, pageSize, 0, null);
    }

    public static <T> PageResult<T> of(List<T> records, long total, int pageNum, int pageSize) {
        int pages = pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        return new PageResult<>(records, total, pageNum, pageSize, pages, null);
    }

    /** 游标翻页结果：不返回 total，规避 ES 深分页的排序与内存开销。 */
    public static <T> PageResult<T> ofCursor(List<T> records, int pageSize, String nextCursor) {
        return new PageResult<>(records, null, null, pageSize, null, nextCursor);
    }
}
