package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SearchHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 搜索历史表 Mapper：按用户写入/去重/清理历史记录。
 */
@Mapper
public interface SearchHistoryMapper extends BaseMapper<SearchHistory> {
}
