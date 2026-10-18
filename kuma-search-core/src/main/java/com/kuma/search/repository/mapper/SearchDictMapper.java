package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SearchDict;
import org.apache.ibatis.annotations.Mapper;

/**
 * 词典表 Mapper：读取启用词条供分词/同义词链重建，并支持词条维护。
 */
@Mapper
public interface SearchDictMapper extends BaseMapper<SearchDict> {
}
