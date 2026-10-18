package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SearchOutbox;
import org.apache.ibatis.annotations.Mapper;

/**
 * Outbox 表 Mapper：扫描待投递事件、更新重试次数与状态，是异步写入 ES 的驱动数据源。
 */
@Mapper
public interface SearchOutboxMapper extends BaseMapper<SearchOutbox> {
}
