package com.kuma.search.service;

import com.kuma.search.dto.resp.SuggestDTO;

import java.util.List;

/**
 * 搜索建议服务。
 *
 * <p>建议结果的来源有<b>三条</b>，按优先级合并去重：
 * <ol>
 *   <li>个人历史 —— 用户搜过的词，输入前缀匹配时最相关；</li>
 *   <li>热词榜 —— 全站高频词，用于「不知道搜什么」的场景；</li>
 *   <li>索引建议 —— 来自 completion suggester，覆盖标题等文档内容。</li>
 * </ol>
 * 优先级顺序很重要：用户刚搜过的词应该排在最前面，而不是被热门词挤掉。
 */
public interface SuggestService {

    /**
     * 获取搜索建议。
     *
     * @param typeCode 类型编码
     * @param prefix   用户已输入的前缀
     * @param size     返回条数上限
     * @param userId   当前用户 ID，用于合并个人历史；为 null 时跳过历史来源
     */
    List<SuggestDTO> suggest(String typeCode, String prefix, Integer size, Long userId);
}
