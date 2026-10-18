package com.kuma.search.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kuma.search.common.result.PageResult;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.repository.mapper.SearchContentMapper;
import com.kuma.search.service.ContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 内容管理后台服务：分页查询走本地 Mapper，写操作委托 core 的 {@link ContentService}。
 *
 * <p>写操作之所以必须走 {@code ContentService} 而不是直接操作 Mapper：那里在同一事务里
 * 同时写业务表与 Outbox 事件，直接写 Mapper 会绕过事件、导致 ES 永远搜不到新数据。
 */
@Service
@RequiredArgsConstructor
public class ContentAdminService {

    private final SearchContentMapper searchContentMapper;
    private final ContentService contentService;

    /**
     * 分页查询内容。
     *
     * <p>四个过滤条件都可省略，省略即"不过滤"（而不是"取某个默认值"）：
     * {@code status} 尤其如此 —— 它的 {@code 0} 是有意义的取值（待审），
     * 用一个 {@code int} 接收会让"不过滤"和"只看待审"撞成同一个值，
     * 所以这里收 {@code Integer} 而不是 {@code int}。
     *
     * <p>{@code authorId} 是前台"我的投稿"的过滤口径：那里强制按当前登录用户过滤，
     * 后台则可以用它查某个作者的全部内容。
     */
    public PageResult<SearchContent> page(String typeCode, int pageNum, int pageSize,
                                          String keyword, Integer status, String authorId) {
        Page<SearchContent> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SearchContent> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(typeCode)) {
            wrapper.eq(SearchContent::getTypeCode, typeCode);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(SearchContent::getTitle, keyword);
        }
        if (status != null) {
            wrapper.eq(SearchContent::getStatus, status);
        }
        if (StringUtils.hasText(authorId)) {
            wrapper.eq(SearchContent::getAuthorId, authorId);
        }
        wrapper.orderByDesc(SearchContent::getId);
        Page<SearchContent> result = searchContentMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
    }

    public SearchContent get(String typeCode, String bizId) {
        return contentService.get(typeCode, bizId);
    }

    /** 新增/更新内容（幂等，以 typeCode+bizId 为准）。 */
    public Long save(SearchContent content) {
        return contentService.save(content);
    }

    public void delete(String typeCode, String bizId) {
        contentService.delete(typeCode, bizId);
    }

    /** 批量导入内容。 */
    public int batchSave(List<SearchContent> contents) {
        return contentService.batchSave(contents);
    }
}
