package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.IndexTypeConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 索引类型配置表 Mapper：读取类型定义驱动动态 mapping 生成与别名切换。
 */
@Mapper
public interface IndexTypeConfigMapper extends BaseMapper<IndexTypeConfig> {
}
