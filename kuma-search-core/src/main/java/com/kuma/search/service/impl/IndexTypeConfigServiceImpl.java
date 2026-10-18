package com.kuma.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuma.search.cache.CacheKeys;
import com.kuma.search.cache.CacheService;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.constant.ContentFields;
import com.kuma.search.dto.resp.ContentFieldSchemaDTO;
import com.kuma.search.dto.resp.ContentTypeSchemaDTO;
import com.kuma.search.dto.resp.SearchTypeDTO;
import com.kuma.search.es.mapping.FieldConfig;
import com.kuma.search.es.mapping.FieldType;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.es.mapping.MappingGenerator;
import com.kuma.search.repository.entity.IndexTypeConfig;
import com.kuma.search.repository.mapper.IndexTypeConfigMapper;
import com.kuma.search.service.IndexTypeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 索引类型配置的实现。
 *
 * <h3>缓存策略：缓存 JSON 字符串而不是实体对象</h3>
 * 这里刻意没有把 {@code IndexTypeConfig} 直接丢进缓存，而是先序列化成字符串再缓存。
 * 原因是二级缓存的 Redis 序列化器开启了 default typing（为了支持多态），
 * 而实体里的 {@code LocalDateTime} 与 MyBatis-Plus 注入的代理字段在
 * 跨版本反序列化时容易出问题；缓存字符串则没有任何类型歧义，
 * 反序列化用 Spring 的 ObjectMapper 显式完成，出问题也能明确报在哪一行。
 *
 * <p>配置是「读多写极少」的数据：10 万 QPS 的检索每秒要解析几万次类型定义，
 * 每次都查一遍 MySQL 是没必要的。TTL 给 30 分钟，且在写操作时主动失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexTypeConfigServiceImpl implements IndexTypeConfigService {

    private final IndexTypeConfigMapper indexTypeConfigMapper;
    private final MappingGenerator mappingGenerator;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    /** 类型配置的缓存时长（秒）。配置变更时会主动失效，所以可以给得比较长。 */
    private static final long CONFIG_TTL_SECONDS = 1800;

    private static final int STATUS_ENABLED = 1;
    private static final int ALLOW_USER_SUBMIT = 1;
    private static final int DEFAULT_SHARDS = 3;
    private static final int DEFAULT_REPLICAS = 0;

    /**
     * 有**独立检索入口**、因此不出现在公开类型列表里的类型。
     *
     * <p>{@code webpage}（网页采集）走的是 {@code POST /api/search/web}，在结果页上与站内结果
     * 并列成两个分组。把它放进站内类型选择器只会带来一种结果：用户选中它、以为在限定检索范围，
     * 而站内那一组给出的是"没有找到相关内容" —— 他选了一个根本不属于这条查询链路的类型。
     *
     * <p>写成常量而不是配置项：这是<b>架构约束</b>（该类型有专属入口与专属响应结构），
     * 不是运维参数。将来若再有第二种有专属入口的类型，加进这个集合，
     * 并确认它的公开入口已经列进 {@code SecurityConfig} 的放行清单。
     */
    private static final Set<String> DEDICATED_SEARCH_TYPES = Set.of("webpage");

    // ==================================================================================
    //  读
    // ==================================================================================

    @Override
    public List<IndexTypeConfig> listAll() {
        return indexTypeConfigMapper.selectList(
                new LambdaQueryWrapper<IndexTypeConfig>().orderByAsc(IndexTypeConfig::getId));
    }

    /**
     * 公开的可检索类型列表。
     *
     * <p>缓存策略与 {@link #getByTypeCode} 一致（缓存 JSON 字符串，理由见类注释），
     * 但**失败时的处理刻意不同**：那里读不到配置就抛异常（检索没有类型定义根本无法执行），
     * 这里只在日志里报错并返回空列表。因为这份数据的唯一用途是渲染一个选择器，
     * 为它抛 500 只是把一次**可自愈**的缓存异常，换成用户搜索页上一次没有意义的报错 ——
     * 而网页采集那一组本来就不需要 typeCode，用户本可以正常搜索。
     * 少一个下拉选项，不值得让整个接口变成 5xx。
     */
    @Override
    public List<SearchTypeDTO> listSearchableTypes() {
        String json = cacheService.get(CacheKeys.indexTypeList(), CONFIG_TTL_SECONDS,
                String.class, this::loadSearchableTypesJson);

        if (json == null) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, SearchTypeDTO[].class));
        } catch (Exception e) {
            // 与 getByTypeCode 的同类分支一样先清缓存：坏数据留在里面会一直坏下去，
            // 而且下一次请求回源就能拿到正确的值，这个错误是自愈的。
            log.error("可检索类型列表反序列化失败，已清除缓存", e);
            cacheService.evict(CacheKeys.indexTypeList());
            return List.of();
        }
    }

    /**
     * 回源：查启用中的类型并映射成公开视图。
     *
     * <p>{@code status} 的过滤放在 SQL 里而不是查出来再筛：不给停用类型任何
     * "先读进内存再被丢掉"的机会，也省掉一次读全表的可能。
     */
    private String loadSearchableTypesJson() {
        List<IndexTypeConfig> configs = indexTypeConfigMapper.selectList(
                new LambdaQueryWrapper<IndexTypeConfig>()
                        .eq(IndexTypeConfig::getStatus, STATUS_ENABLED)
                        .orderByAsc(IndexTypeConfig::getId));

        List<SearchTypeDTO> types = configs.stream()
                .filter(config -> !DEDICATED_SEARCH_TYPES.contains(config.getTypeCode()))
                .map(config -> SearchTypeDTO.builder()
                        .typeCode(config.getTypeCode())
                        // 名字缺失时退回编码：下拉框里出现一个没有文字的空行，
                        // 比出现一个英文编码更难让管理员意识到"这个类型的 type_name 没填"
                        .typeName(config.getTypeName() == null || config.getTypeName().isBlank()
                                ? config.getTypeCode() : config.getTypeName())
                        .build())
                .toList();

        try {
            return objectMapper.writeValueAsString(types);
        } catch (Exception e) {
            throw BizException.of(ResultCode.SYSTEM_ERROR, "可检索类型列表序列化失败", e);
        }
    }

    @Override
    public List<ContentTypeSchemaDTO> listContentSchemas(boolean onlySubmittable) {
        LambdaQueryWrapper<IndexTypeConfig> query = new LambdaQueryWrapper<IndexTypeConfig>()
                .eq(IndexTypeConfig::getStatus, STATUS_ENABLED);
        if (onlySubmittable) {
            query.eq(IndexTypeConfig::getAllowUserSubmit, ALLOW_USER_SUBMIT);
        }
        return indexTypeConfigMapper.selectList(query.orderByAsc(IndexTypeConfig::getId))
                .stream()
                .map(this::toContentSchema)
                .toList();
    }

    @Override
    public ContentTypeSchemaDTO getContentSchema(String typeCode) {
        // 复用 getByTypeCode：停用与不存在的类型都当作不存在，且共享同一层缓存。
        // 表单结构与类型配置是同一份数据的两个视图，视图之间的取舍不该有第二套规则。
        return toContentSchema(getByTypeCode(typeCode));
    }

    /**
     * 把类型配置投影成「表单能画什么」。
     *
     * <p>不返回 analyzer / boost / filters 这些索引期参数：它们决定"字段怎么被检索"，
     * 与"字段该填什么"无关，返回了只会让表单渲染器多出一堆无法解释的开关。
     */
    private ContentTypeSchemaDTO toContentSchema(IndexTypeConfig config) {
        List<ContentFieldSchemaDTO> fields = mappingGenerator
                .parseFieldConfig(config.getFieldConfig())
                .stream()
                .filter(field -> !ContentFields.isReserved(field.getName()))
                .map(this::toFieldSchema)
                .toList();

        return ContentTypeSchemaDTO.builder()
                .typeCode(config.getTypeCode())
                // 名字缺失时退回编码：表单标题上出现一个空白比出现一个英文编码更难让人意识到"没填名称"
                .typeName(config.getTypeName() == null || config.getTypeName().isBlank()
                        ? config.getTypeCode() : config.getTypeName())
                .allowUserSubmit(config.getAllowUserSubmit() != null
                        && config.getAllowUserSubmit() == ALLOW_USER_SUBMIT)
                .fields(fields)
                .serverManagedFields(ContentFields.SERVER_MANAGED_ON_SUBMIT)
                .build();
    }

    private ContentFieldSchemaDTO toFieldSchema(FieldConfig field) {
        FieldType type = field.getType() == null ? FieldType.TEXT : field.getType();
        return ContentFieldSchemaDTO.builder()
                .name(field.getName())
                .label(field.getLabel() == null || field.getLabel().isBlank()
                        ? field.getName() : field.getLabel())
                .type(type.name())
                .multiValued(Boolean.TRUE.equals(field.getMultiValued()))
                .format(field.getFormat())
                .inExtra(!ContentFields.isEntityColumn(field.getName()))
                .build();
    }

    @Override
    public IndexTypeConfig getByTypeCode(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            throw BizException.paramInvalid("typeCode 不能为空");
        }

        String json = cacheService.get(CacheKeys.indexTypeConfig(typeCode), CONFIG_TTL_SECONDS,
                String.class, () -> loadConfigJson(typeCode));

        if (json == null) {
            throw BizException.of(ResultCode.SEARCH_TYPE_NOT_FOUND,
                    "搜索类型未配置或已停用: " + typeCode);
        }

        try {
            return objectMapper.readValue(json, IndexTypeConfig.class);
        } catch (Exception e) {
            // 缓存里存了坏数据时不能把整个检索链路拖死：清掉缓存，下一个请求自然会回源。
            log.error("类型配置反序列化失败，已清除缓存，type={}", typeCode, e);
            cacheService.evict(CacheKeys.indexTypeConfig(typeCode));
            throw BizException.of(ResultCode.SYSTEM_ERROR, "类型配置读取失败", e);
        }
    }

    /**
     * 回源：查库并序列化。
     *
     * <p>停用的类型（{@code status != 1}）在这里直接当作「不存在」返回 null，
     * 让缓存写入空值占位。这样停用一个类型是<b>即时生效</b>的 ——
     * 不需要去遍历清理散落在各处的调用方。
     */
    private String loadConfigJson(String typeCode) {
        IndexTypeConfig config = indexTypeConfigMapper.selectOne(
                new LambdaQueryWrapper<IndexTypeConfig>()
                        .eq(IndexTypeConfig::getTypeCode, typeCode)
                        .last("LIMIT 1"));

        if (config == null) {
            return null;
        }
        if (!Integer.valueOf(STATUS_ENABLED).equals(config.getStatus())) {
            log.debug("类型 {} 已停用，按不存在处理", typeCode);
            return null;
        }

        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw BizException.of(ResultCode.SYSTEM_ERROR, "类型配置序列化失败", e);
        }
    }

    // ==================================================================================
    //  配置 -> 索引定义
    // ==================================================================================

    /**
     * 把持久化配置解析成索引定义。
     *
     * <p>解析过程会做<b>一致性补全</b>而不是只做转换：管理员在 {@code search_config} 里
     * 没写的项（默认检索字段、高亮字段、摘要字段）都从 {@code field_config} 推导出合理默认值。
     * 否则每个新类型都必须把两个 JSON 写全才能用，配置成本过高，
     * 而配置成本高的功能最终一定会被人绕过 —— 转而把字段硬编码回代码里。
     */
    @Override
    public IndexDefinition resolveDefinition(String typeCode) {
        IndexTypeConfig config = getByTypeCode(typeCode);

        List<FieldConfig> fields = mappingGenerator.parseFieldConfig(config.getFieldConfig());
        if (fields.isEmpty()) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "类型 " + typeCode + " 未配置任何字段，无法检索。请先在后台配置 field_config");
        }

        String pkField = config.getPkField() == null || config.getPkField().isBlank()
                ? "id" : config.getPkField();
        if (fields.stream().noneMatch(f -> pkField.equals(f.getName()))) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "主键字段 " + pkField + " 未在 field_config 中声明。"
                            + "主键是排序唯一性的兜底依据，必须存在于索引中");
        }

        JsonNode searchConfig = mappingGenerator.parseSearchConfig(config.getSearchConfig());

        return IndexDefinition.builder()
                .typeCode(config.getTypeCode())
                .alias(config.getIndexAlias() != null && !config.getIndexAlias().isBlank()
                        ? config.getIndexAlias() : defaultAlias(config.getTypeCode()))
                .version(config.getCurrentVersion() == null ? 1 : config.getCurrentVersion())
                .shards(config.getShards())
                .replicas(config.getReplicas())
                .pkField(pkField)
                .fields(fields)
                .defaultSearchFields(resolveSearchFields(searchConfig, fields))
                .defaultHighlightFields(resolveHighlightFields(searchConfig, fields))
                .defaultSort(resolveDefaultSort(searchConfig))
                .summaryFields(resolveSummaryFields(searchConfig, fields))
                .build();
    }

    private String defaultAlias(String typeCode) {
        return "kuma_" + typeCode;
    }

    /**
     * 默认检索字段：显式配置优先；未配置时取所有 searchable 的字段，
     * 并按 boost 降序排列 —— 顺序即 {@code multi_match} 的字段顺序，影响打分。
     */
    private List<String> resolveSearchFields(JsonNode searchConfig, List<FieldConfig> fields) {
        List<String> configured = readStringArray(searchConfig, "defaultSearchFields");
        if (!configured.isEmpty()) {
            return configured;
        }
        return fields.stream()
                .filter(f -> !Boolean.FALSE.equals(f.getSearchable()))
                .sorted(Comparator.comparing(
                        (FieldConfig f) -> f.getBoost() == null ? 1.0f : f.getBoost()).reversed())
                .map(FieldConfig::getName)
                .toList();
    }

    private List<String> resolveHighlightFields(JsonNode searchConfig, List<FieldConfig> fields) {
        List<String> configured = readStringArray(searchConfig, "defaultHighlightFields");
        if (!configured.isEmpty()) {
            return configured;
        }
        List<String> result = fields.stream()
                .filter(f -> Boolean.TRUE.equals(f.getHighlight()))
                .map(FieldConfig::getName)
                .toList();
        // 兜底：一个都没配就高亮第一个可检索的 TEXT 字段。
        // 结果列表上没有任何高亮，用户很难一眼看出"为什么这条命中了"。
        if (result.isEmpty()) {
            return fields.stream()
                    .filter(f -> f.getType() == FieldType.TEXT)
                    .map(FieldConfig::getName)
                    .findFirst()
                    .map(List::of)
                    .orElse(List.of());
        }
        return result;
    }

    private List<String> resolveSummaryFields(JsonNode searchConfig, List<FieldConfig> fields) {
        List<String> configured = readStringArray(searchConfig, "summaryFields");
        if (!configured.isEmpty()) {
            return configured;
        }
        return fields.stream()
                .filter(f -> f.getType() == FieldType.TEXT)
                .map(FieldConfig::getName)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> resolveDefaultSort(JsonNode searchConfig) {
        JsonNode node = searchConfig.get("defaultSort");
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (JsonNode item : node) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("field", item.path("field").asText(null));
            entry.put("order", item.path("order").asText("desc"));
            if (entry.get("field") != null && !entry.get("field").isBlank()) {
                result.add(entry);
            }
        }
        return result;
    }

    private List<String> readStringArray(JsonNode parent, String key) {
        JsonNode node = parent.get(key);
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    // ==================================================================================
    //  写
    // ==================================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(IndexTypeConfig config) {
        if (config.getTypeCode() == null || config.getTypeCode().isBlank()) {
            throw BizException.paramInvalid("typeCode 不能为空");
        }
        // 类型编码会拼进索引名与别名，必须先约束字符集 ——
        // ES 索引名不允许大写字母，且以 _ - + 开头会被拒绝，让它在写入前就失败并给出明确提示。
        validateTypeCode(config.getTypeCode());

        validateFieldConfig(config.getFieldConfig());

        Long exists = indexTypeConfigMapper.selectCount(new LambdaQueryWrapper<IndexTypeConfig>()
                .eq(IndexTypeConfig::getTypeCode, config.getTypeCode()));
        if (exists != null && exists > 0) {
            throw BizException.of(ResultCode.INDEX_TYPE_CODE_EXISTS,
                    "类型编码已存在: " + config.getTypeCode());
        }

        config.setIndexAlias(config.getIndexAlias() == null || config.getIndexAlias().isBlank()
                ? defaultAlias(config.getTypeCode()) : config.getIndexAlias());
        config.setCurrentVersion(config.getCurrentVersion() == null ? 1 : config.getCurrentVersion());
        config.setShards(config.getShards() == null ? DEFAULT_SHARDS : config.getShards());
        config.setReplicas(config.getReplicas() == null ? DEFAULT_REPLICAS : config.getReplicas());
        config.setPkField(config.getPkField() == null || config.getPkField().isBlank()
                ? "id" : config.getPkField());
        config.setStatus(config.getStatus() == null ? STATUS_ENABLED : config.getStatus());
        // 投稿开关默认关闭，且不因为「这是新建的类型」而继承任何东西 ——
        // 它必须由配置的人显式打开，见字段注释。
        config.setAllowUserSubmit(Integer.valueOf(ALLOW_USER_SUBMIT).equals(config.getAllowUserSubmit())
                ? ALLOW_USER_SUBMIT : 0);

        try {
            indexTypeConfigMapper.insert(config);
        } catch (DuplicateKeyException e) {
            // 唯一键冲突兜到这里，覆盖"并发同时创建"以及"唯一键包含逻辑删除行"的情况。
            throw BizException.of(ResultCode.INDEX_TYPE_CODE_EXISTS,
                    "类型编码已存在: " + config.getTypeCode());
        }

        log.info("新增索引类型配置: typeCode={}, alias={}", config.getTypeCode(), config.getIndexAlias());
        return config.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(IndexTypeConfig config) {
        if (config.getId() == null) {
            throw BizException.paramInvalid("更新类型配置必须提供 id");
        }

        IndexTypeConfig existing = indexTypeConfigMapper.selectById(config.getId());
        if (existing == null) {
            throw BizException.notFound("类型配置不存在: id=" + config.getId());
        }

        if (config.getFieldConfig() != null) {
            validateFieldConfig(config.getFieldConfig());
        }

        // typeCode 与 alias 是索引身份的组成部分，不允许改。
        // 允许改的话，旧索引与别名会变成没人认识的孤儿，且线上正在服务的检索会瞬间失效。
        config.setTypeCode(null);
        config.setIndexAlias(null);
        config.setCurrentVersion(null);

        indexTypeConfigMapper.updateById(config);
        invalidate(existing.getTypeCode());

        log.info("更新索引类型配置: typeCode={}, id={}", existing.getTypeCode(), config.getId());
    }

    /**
     * 逻辑删除类型配置。
     *
     * <p>⚠️ 本方法<b>不做任何前置检查</b>，两个前置条件由调用方（后台的
     * {@code IndexTypeController#delete}）负责把关，它们是<b>顺序敏感</b>的：
     * <ol>
     *   <li>该类型的 ES 索引必须已经删掉。反过来的话，配置一被逻辑删除，
     *       {@code getByTypeCode} 就把它当作不存在，而 {@code DynamicIndexManager#deleteIndex}
     *       内部要解析类型定义 —— <b>索引从此再也删不掉了</b>，只能登进 ES 手工删除；</li>
     *   <li>该类型下不能还有未删除的内容。有的话那些内容会变成"查不到也管不了"的孤儿：
     *       检索要类型定义、删除也要类型定义（{@code ContentServiceImpl#delete} 会先解析类型）。</li>
     * </ol>
     * 放在控制层而不是这里，是因为这两条判断需要跨到 ES 与内容表，属于管理策略而非配置表自身的不变式；
     * 且 core 里注入 {@code DynamicIndexManager} 会与它对本服务的依赖形成构造器循环。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        IndexTypeConfig existing = indexTypeConfigMapper.selectById(id);
        if (existing == null) {
            throw BizException.notFound("类型配置不存在: id=" + id);
        }

        // 这里只做逻辑删除配置。ES 里的物理索引不会跟着删 ——
        // 删索引是不可逆的破坏性操作，必须由管理员在索引管理界面显式确认后再执行。
        indexTypeConfigMapper.deleteById(id);
        invalidate(existing.getTypeCode());

        log.warn("已删除类型配置: typeCode={}。ES 索引未删除，如需清理请单独操作", existing.getTypeCode());
    }

    /**
     * 失效一份类型配置的缓存。
     *
     * <p>公开类型列表必须一起失效：它与 {@code indexTypeConfig(typeCode)} 是同一张表的两个视图
     * （见 {@code CacheKeys#indexTypeList}），只清一个的话，「停用一个类型」这件事
     * 在检索链路上是即时生效的，在选择器里却要等到 TTL 到期才消失 ——
     * 一个能被用户看见、却没有任何报错的不一致。
     */
    private void invalidate(String typeCode) {
        cacheService.evict(CacheKeys.indexTypeConfig(typeCode));
        cacheService.evict(CacheKeys.indexTypeList());
    }

    // ==================================================================================
    //  校验
    // ==================================================================================

    /**
     * 校验字段配置。
     *
     * <p>这里拒绝的都是「能保存成功但运行期必炸」的配置，而不是吹毛求疵：
     * 让错误在保存时就暴露，胜过让它在某天的一次查询里变成
     * {@code Fielddata is disabled on text fields} 这种没人看得懂的报错。
     */
    @Override
    public void validateFieldConfig(String fieldConfigJson) {
        // 交给 MappingGenerator 解析，它能给出比 NPE 有用得多的错误信息
        List<FieldConfig> fields = mappingGenerator.parseFieldConfig(fieldConfigJson);

        if (fields.isEmpty()) {
            throw BizException.paramInvalid("field_config 不能为空数组");
        }

        Set<String> names = new HashSet<>();
        for (FieldConfig field : fields) {
            if (field.getName() == null || field.getName().isBlank()) {
                throw BizException.paramInvalid("存在未命名字段（name 不能为空）");
            }
            if (!names.add(field.getName())) {
                throw BizException.paramInvalid("字段名重复: " + field.getName());
            }

            FieldType type = field.getType() == null ? FieldType.TEXT : field.getType();

            // TEXT 的 doc_values 是关闭的，聚合与排序都会落到 .keyword 子字段上。
            // 这在 MappingGenerator 里已经被照顾到了（TEXT 一定生成 .keyword），
            // 但 NESTED 没有对应子字段，必须直接拒绝。
            if (type == FieldType.NESTED
                    && (Boolean.TRUE.equals(field.getFacetable())
                    || Boolean.TRUE.equals(field.getSortable()))) {
                throw BizException.paramInvalid(
                        "字段 " + field.getName() + " 是 nested 类型，不能直接聚合或排序，"
                                + "请改用其内部字段（以「父.子」命名单独声明）");
            }

            if (Boolean.TRUE.equals(field.getSuggest())
                    && type != FieldType.TEXT && type != FieldType.KEYWORD) {
                throw BizException.paramInvalid(
                        "字段 " + field.getName() + " 声明了 suggest=true，"
                                + "但只有 text / keyword 类型可以生成补全建议");
            }

            if (Boolean.TRUE.equals(field.getPinyin())
                    && type != FieldType.TEXT && type != FieldType.KEYWORD) {
                throw BizException.paramInvalid(
                        "字段 " + field.getName() + " 声明了 pinyin=true，"
                                + "但只有 text / keyword 类型可以生成拼音子字段");
            }

            if (field.getAnalyzer() != null && !field.getAnalyzer().isBlank()
                    && type != FieldType.TEXT) {
                throw BizException.paramInvalid(
                        "字段 " + field.getName() + " 配置了 analyzer，但类型 " + type
                                + " 不分词，该配置不会生效。请改为 text 类型或去掉 analyzer");
            }

            if (field.getSearchAnalyzer() != null && !field.getSearchAnalyzer().isBlank()
                    && type != FieldType.TEXT) {
                throw BizException.paramInvalid(
                        "字段 " + field.getName() + " 配置了 searchAnalyzer，但类型 " + type
                                + " 不分词，该配置不会生效");
            }
        }

        if (names.size() > 200) {
            throw BizException.paramInvalid("字段数量 " + names.size() + " 过多，建议控制在 200 以内");
        }
    }

    /**
     * 校验类型编码。
     *
     * <p>ES 的索引名有硬性约束：只能是小写字母、数字与 {@code _ - + .}，且不能以 {@code _ - +} 开头。
     * 类型编码会被拼进别名与物理索引名，因此必须在这里拦住。
     * 等到建索引时才报错的话，管理员已经在后台把字段配完了，返工成本高得多。
     */
    private void validateTypeCode(String typeCode) {
        if (!typeCode.matches("^[a-z][a-z0-9_]{0,62}$")) {
            throw BizException.paramInvalid(
                    "类型编码 " + typeCode + " 不合法：只能用小写字母、数字与下划线，"
                            + "必须以字母开头，长度不超过 63 —— 因为它会被拼进 ES 的索引名");
        }
    }
}
