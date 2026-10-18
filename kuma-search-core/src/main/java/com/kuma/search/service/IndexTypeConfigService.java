package com.kuma.search.service;

import com.kuma.search.dto.resp.ContentTypeSchemaDTO;
import com.kuma.search.dto.resp.SearchTypeDTO;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.repository.entity.IndexTypeConfig;

import java.util.List;

/**
 * 索引类型配置的读写。
 *
 * <p>这个服务是「通用多类型」需求的入口：管理员在这里登记一个新类型（比如 {@code product}）、
 * 声明它的字段，系统就能为它建索引、建建议索引、开放检索能力，<b>不需要改代码发版</b>。
 */
public interface IndexTypeConfigService {

    /**
     * 列出**全部**类型配置，含已停用的，且不过滤任何类型。
     *
     * <p>这是给管理后台用的：停用的类型也必须出现在配置列表里，否则管理员停用之后就再也找不到
     * 它、无法重新启用。也正因为它带着 {@code field_config} / {@code search_config}，
     * <b>绝不能用它来实现面向用户的接口</b> —— 公开的类型列表走
     * {@link #listSearchableTypes()}。
     */
    List<IndexTypeConfig> listAll();

    /**
     * 列出**公开可检索**的类型，供搜索页的类型选择器使用。
     *
     * <p>与 {@link #listAll()} 的三点区别，每一点都是刻意的：
     * <ol>
     *   <li>只返回启用中的（{@code status=1}）—— 与 {@link #getByTypeCode} 把停用类型
     *       当作不存在是同一条规则，否则用户能从选择器里选到一个检索必然报错的类型；</li>
     *   <li>排除有独立检索入口的类型（如 {@code webpage}，见实现类的常量说明）；</li>
     *   <li>只带 {@code typeCode} 与 {@code typeName}，不带内部 mapping 配置。</li>
     * </ol>
     *
     * <p>返回值可能为空列表（还没有配置任何类型），调用方必须能处理 —— 空不等于出错。
     */
    List<SearchTypeDTO> listSearchableTypes();

    /** 按类型编码取配置，不存在时抛 {@code BizException}。 */
    IndexTypeConfig getByTypeCode(String typeCode);

    /**
     * 列出内容表单的结构（每个启用类型一条），供后台内容维护页与前台投稿页渲染动态表单。
     *
     * @param onlySubmittable true 时只返回 {@code allow_user_submit=1} 的类型（前台投稿页用），
     *                        false 时返回全部启用类型（后台内容页用）
     */
    List<ContentTypeSchemaDTO> listContentSchemas(boolean onlySubmittable);

    /**
     * 取单个类型的内容表单结构。
     *
     * <p>与 {@link #getByTypeCode} 一样，停用或不存在的类型抛业务异常而不是返回空对象：
     * 调用方拿着一个 typeCode 却拿不到结构，说明这个 typeCode 是错的，静默返回空表单
     * 只会让人以为"这个类型没有字段"。
     */
    ContentTypeSchemaDTO getContentSchema(String typeCode);

    /**
     * 把数据库配置解析成索引定义。
     *
     * <p>这是配置（持久化模型）与索引（ES 模型）之间的防腐层：
     * 解析 {@code field_config} / {@code search_config} 两个 JSON 字段，
     * 校验字段声明的一致性，产出 {@code MappingGenerator} 能直接消费的对象。
     *
     * @throws com.kuma.search.common.exception.BizException 配置非法时抛出，且错误信息要能定位到具体字段
     */
    IndexDefinition resolveDefinition(String typeCode);

    /** 创建类型配置。typeCode 必须唯一。 */
    Long create(IndexTypeConfig config);

    /** 更新类型配置。注意：修改字段类型不会自动生效，需要重建索引。 */
    void update(IndexTypeConfig config);

    /** 逻辑删除类型配置。不会删除 ES 索引，需另行调用索引删除。 */
    void delete(Long id);

    /** 校验字段配置 JSON 的合法性，供管理后台「保存前校验」使用。 */
    void validateFieldConfig(String fieldConfigJson);
}
