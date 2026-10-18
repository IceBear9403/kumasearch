package com.kuma.search.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.repository.entity.SearchDict;
import com.kuma.search.repository.mapper.SearchDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 词典管理（admin 专有）。
 *
 * <p>词典变更后需要重建分析器才生效，本类只负责数据落库，不触发重建 —— 避免一次编辑就打一次 ES。
 */
@Service
@RequiredArgsConstructor
public class DictService {

    private final SearchDictMapper searchDictMapper;

    /** 按词典类型查询；{@code dictType} 为空时返回全部。 */
    public List<SearchDict> list(String dictType) {
        LambdaQueryWrapper<SearchDict> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(dictType)) {
            wrapper.eq(SearchDict::getDictType, dictType);
        }
        wrapper.orderByDesc(SearchDict::getId);
        return searchDictMapper.selectList(wrapper);
    }

    /**
     * 新增或更新词典：以 id 是否为空区分。
     *
     * @return 记录主键
     */
    public Long save(SearchDict dict) {
        if (dict.getId() == null) {
            searchDictMapper.insert(dict);
        } else {
            searchDictMapper.updateById(dict);
        }
        return dict.getId();
    }

    public void delete(Long id) {
        searchDictMapper.deleteById(id);
    }
}
