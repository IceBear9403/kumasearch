package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.CrawlAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 采集审核留痕 Mapper。
 *
 * <p>只有插入与查询，没有任何 {@code update}/{@code delete} 的调用点 ——
 * 这不是遗漏，是这个表的设计约束，见 {@link CrawlAuditLog} 的类注释。
 * {@code BaseMapper} 提供的方法里确实带着这两个能力，落地时不要用它们。
 */
@Mapper
public interface CrawlAuditLogMapper extends BaseMapper<CrawlAuditLog> {
}
