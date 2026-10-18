package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.CrawlSeed;
import org.apache.ibatis.annotations.Mapper;

/**
 * 采集种子站 Mapper。
 *
 * <p>没有自定义查询：种子站数量以「个」计，全量加载后按状态过滤是毫秒级操作，
 * 且 {@code CrawlScheduler} 每轮都需要全部启用中的种子（判配额、算限速都要用），
 * 分开查反而多一次往返。
 */
@Mapper
public interface CrawlSeedMapper extends BaseMapper<CrawlSeed> {
}
