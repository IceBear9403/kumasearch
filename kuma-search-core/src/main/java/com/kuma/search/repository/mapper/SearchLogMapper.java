package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SearchLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检索日志表 Mapper：高频追加写入，并支撑按时间/关键词的无结果与快慢查询分析。
 */
@Mapper
public interface SearchLogMapper extends BaseMapper<SearchLog> {
}
