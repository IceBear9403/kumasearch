package com.kuma.search.es.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把「配置表的字段清单」翻译成「ES 的 settings + mappings 文档」。
 *
 * <p>这是「通用多类型、加类型不发版」这条需求的技术落点。<b>有意采用 JSON 文档树而不是
 * ES Java Client 的链式 Builder</b>，原因有三：
 * <ol>
 *   <li>生成目标是<b>一份文档</b>而不是强类型对象，mapping 的形态由运行期配置决定，
 *       链式 Builder 的联合类型（Property / Analyzer 全是 union）会带来大量嵌套噪音；</li>
 *   <li>JSON 可被后台「mapping 预览」接口直接返回给管理员核对，所见即所得；</li>
 *   <li>mapping 生成逻辑可以纯单元测试，不需要起 ES。</li>
 * </ol>
 *
 * <h3>三个必须遵守的 ES 约束（写在这里，避免后人踩坑）</h3>
 * <ol>
 *   <li><b>{@code synonym_graph} 只能出现在 search_analyzer 里</b>，放索引期会直接报错。</li>
 *   <li><b>completion 字段不能建在主索引上</b> —— 它的 FST 常驻堆内存，文档量上来必然 OOM。
 *       因此本类把建议字段全部放到独立的 {@code {alias}_suggest} 轻量索引里，
 *       见 {@link #buildSuggestIndexJson}。</li>
 *   <li><b>不同 analyzer 必须拆成多字段（multi-field），不能叠加在同一条链上</b>。
 *       IK 与 pinyin 若串在同一个 analyzer 里，会产生大量重复 token，
 *       索引膨胀数倍且相关度计算失真。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MappingGenerator {

    private final ObjectMapper objectMapper;

    // ===== 全局 analyzer / tokenizer 名称常量，供查询期引用，避免字符串散落各处 =====

    /** 索引期分析器：IK 细粒度切分，召回率高。 */
    public static final String ANALYZER_IK_MAX_WORD = "ik_max_word";
    /** 查询期分析器：IK 粗粒度 + 小写归一 + 同义词图。 */
    public static final String ANALYZER_SEARCH = "kuma_search_analyzer";
    /** 拼音分析器：全拼 + 首字母，用于「shouji」「sj」这类输入。 */
    public static final String ANALYZER_PINYIN = "kuma_pinyin_analyzer";
    /** 拼音首字母分析器：只产出首字母，用于纯缩写检索。 */
    public static final String ANALYZER_PINYIN_FPY = "kuma_pinyin_fpy_analyzer";
    /**
     * 联想索引专用的分析器：整个 input 当作一个 token + 转小写。
     *
     * <p>只存在于联想索引的 settings 里（{@link #buildSuggestAnalysis}），主索引不定义它 ——
     * 主索引需要的是切分，联想需要的是"整串当一项"，两者正好相反。
     */
    public static final String ANALYZER_SUGGEST = "kuma_suggest_analyzer";

    /**
     * 联想索引上的拼音分析器：给一段中文产出「连写全拼」与「首字母」两种 token。
     *
     * <h3>它不是给 suggest 字段用的</h3>
     * 与 {@link #ANALYZER_SUGGEST} 不同，这个分析器<b>不参与检索</b>，
     * 只在灌词时被 {@code _analyze} 调用一次：把标题转成拼音串，再由灌词逻辑
     * 当作 suggest 字段的 input 写进去（见 {@code SuggestWordServiceImpl}）。
     * 查询期永远不会用到它 —— 输入 "jy" 是拿 {@link #ANALYZER_SUGGEST} 分析后
     * 去 FST 里做前缀匹配的。
     *
     * <h3>为什么借 ES 的插件，而不是引一个 Java 拼音库</h3>
     * 集群上已经装了 {@code analysis-pinyin}（主索引的拼音子字段就靠它），
     * 再引一个 Java 拼音库会出现<b>两套拼音规则</b>：主索引认为 "重庆" 是 chongqing，
     * 联想里却是 zhongqing，同一份数据在两个功能里行为不一致，而排查时很难想到
     * 根子上是两个库。借插件则天然只有一套规则。
     *
     * <p>代价是灌词时每条内容都要多一次 {@code _analyze} 往返（见灌词服务的实现说明）。
     *
     * <h3>参数为什么这么配</h3>
     * <ul>
     *   <li>{@code keep_full_pinyin: false} —— 逐字全拼（jiao/yu/ke…）对前缀补全没有用，
     *       只会让 token 数量翻几倍；用户不会输入 "jiao" 去补 "jiaoyu"。</li>
     *   <li>{@code keep_joined_full_pinyin: true} —— 连写全拼（jiaoyukexuechubanshe），
     *       它就是"输入 jiaoyu 出「教育科学出版社」"所依赖的那一条。</li>
     *   <li>{@code keep_first_letter: true} —— 首字母缩写（jykxcbs），对应 "jy" 这类输入。</li>
     *   <li>{@code keep_none_chinese: false} —— 丢掉拉丁字母与数字。标题里的 "-"、"（）"、
     *       站名里的英文若保留，会在拼音串中间插进噪音，把前缀匹配打断。</li>
     *   <li>{@code limit_first_letter_length: 64} —— 默认 16 太短，长标题的首字母串会被截断，
     *       表现为"输全了反而不出"。</li>
     *   <li>{@code remove_duplicated_term: true} —— 去掉重复 token，避免同一个词灌两遍。</li>
     * </ul>
     */
    public static final String ANALYZER_SUGGEST_PINYIN = "kuma_suggest_pinyin";

    /**
     * 联想索引上那个拼音分析器所引用的 tokenizer 的名字。
     *
     * <p>单独拎出来是因为<b>分析器与 tokenizer 是两段配置</b>：
     * tokenizer 定义在 {@code analysis.tokenizer} 里，分析器只能用名字引用它。
     * 名字写错或漏定义的症状，是建索引时直接报
     * {@code Unknown tokenizer type [...] for [kuma_suggest_pinyin]} ——
     * 定义为常量至少能保证这两处不会写岔。
     *
     * <p>不复用主索引的 {@code kuma_pinyin_tokenizer}：那是<b>另一个索引</b>里的定义，
     * ES 的 analysis 配置不跨索引共享（集群级定义只能改 elasticsearch.yml 并重启节点）。
     */
    public static final String TOKENIZER_SUGGEST_PINYIN = "kuma_suggest_pinyin_tokenizer";

    /** 同义词文件在 ES 节点上的相对路径（相对于 config 目录）。 */
    private static final String SYNONYM_FILE = "analysis/synonyms.txt";
    /** IK 扩展词典路径，用于让自定义新词被正确切分。 */
    private static final String IK_EXT_DICT = "analysis/ik_ext_dict.dic";
    /** IK 停用词路径。 */
    private static final String IK_STOP_DICT = "analysis/ik_stop_dict.dic";

    /** 默认分片数：单机轻量部署下 3 个分片已能吃到并行检索的收益，又不会让分片数过多。 */
    private static final int DEFAULT_SHARDS = 3;
    private static final int DEFAULT_REPLICAS = 0;

    /**
     * DATE 字段的默认 format —— 四种写法都要认，其中 {@code 'T'} 那一种是**必须**的。
     *
     * <h3>为什么必须包含 {@code yyyy-MM-dd'T'HH:mm:ss}</h3>
     * {@code DocumentConverter} 写进 ES 的 {@code publishTime} 是
     * {@code LocalDateTime.toString()} 的结果，形如 {@code 2026-10-15T10:30:00}（**带 T**）；
     * 而人在后台表单里填的是 {@code 2026-10-15 10:30:00}（带空格）。
     *
     * <p>ES 的 {@code date} 类型只在**没有显式 format** 时才回落到内置的
     * {@code strict_date_optional_time||epoch_millis}。一旦配置里写了 format（哪怕是本类的默认值），
     * 它就只按那份清单解析 —— 于是清单里少了 {@code 'T'} 变体时，
     * {@code publishTime} 非空的文档会解析失败。失败单位是**整条文档**（除非开 ignore_malformed），
     * 且错误只出现在 bulk 响应里、不会抛异常：现象就是「内容保存成功了、列表里也有，
     * 但搜不到」，排查方向会完全跑偏。
     *
     * <p>把两种写法都列进来，比"让调用方统一格式"更可靠：写入侧有两个来源
     * （实体列走 LocalDateTime.toString()、extra JSON 走人手填），不可能长期保持一致。
     */
    private static final String DEFAULT_DATE_FORMAT =
            "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss||yyyy-MM-dd||epoch_millis";

    // ==================================================================================
    //  对外入口
    // ==================================================================================

    /**
     * 生成主索引的完整建索引请求体（含 aliases + settings + mappings）。
     *
     * <p>返回的是可以<b>整体</b>作为 {@code PUT /{index}} 请求体的 JSON 字符串。
     *
     * <p><b>只用于「首次创建」</b>（{@code DynamicIndexManager#createIndex}）。
     * 重建索引时请用 {@link #buildIndexJsonWithoutAlias}，并且先读完那个方法的 javadoc
     * 再决定用哪一个 —— 两个方法只差一个 aliases 段，但选错的那个会让重建**必然失败**
     * 或者把查询指向一个只灌了一半的索引。
     */
    public String buildIndexJson(IndexDefinition def) {
        return buildBody(def, true);
    }

    /**
     * 生成主索引的建索引请求体，<b>不含 aliases 段</b>。供 {@code rebuildIndex} 建新物理索引用。
     *
     * <h3>为什么重建时不能带别名（这是 rebuildIndex 曾经必然失败的原因）</h3>
     * 别名的写索引归属（{@code is_write_index: true}）是<b>全别名唯一</b>的：
     * 重建时旧索引 {@code kuma_article_v1} 仍挂在别名 {@code kuma_article} 上并占着这个位置，
     * 新索引在创建请求里再宣称自己也是写索引，ES 会直接拒绝整个建索引请求：
     *
     * <pre>
     *   PUT /kuma_article_v2 -&gt; 500
     *   illegal_state_exception: alias [kuma_article] has more than one write index
     *                            [kuma_article_v1,kuma_article_v2]
     * </pre>
     *
     * 而失败的信息量与它的后果完全不成比例：报错只出现在应用侧一行"创建索引失败"，
     * 于是 {@code rebuildIndex} 变成<b>只有当该类型从来没建过索引时才可能成功</b> ——
     * 也就是对任何已经在投产的类型都必然失败，且失败点在第 2 步，
     * 看起来却像是索引建得太慢或 ES 出了问题。
     *
     * <h3>为什么不是"挂上去但把 is_write_index 设成 false"</h3>
     * 那样建索引确实能过，但别名在灌数据期间就<b>同时指向新旧两个索引</b>了。
     * 此时索引里只有一部分文档（灌数据还没跑完），而查询侧是走别名的 ——
     * 结果是「重建完之后搜索结果少了一半」，看起来像分词配置写错了，
     * 与真正的原因（别名提前挂上）毫无关系。别名只在
     * {@code EsIndexGateway#switchAlias} 那个原子的 remove+add 请求里挂，
     * 这是"要么全旧、要么全新"的唯一保证。
     */
    public String buildIndexJsonWithoutAlias(IndexDefinition def) {
        return buildBody(def, false);
    }

    /**
     * 组装建索引请求体。
     *
     * @param includeAlias 是否把 aliases 段写进请求体。真值只对「首次创建」成立，
     *                     理由见 {@link #buildIndexJsonWithoutAlias} 的注释
     */
    private String buildBody(IndexDefinition def, boolean includeAlias) {
        validate(def);
        ObjectNode root = objectMapper.createObjectNode();
        if (includeAlias) {
            root.set("aliases", buildAliases(def));
        }
        root.set("settings", buildSettings(def));
        root.set("mappings", buildMapping(def));
        return toPrettyJson(root);
    }

    /**
     * 生成联想建议索引的建索引请求体。
     *
     * <p><b>为什么必须独立索引</b>：completion suggester 在首次使用时会把整个字段的
     * FST 前缀树加载进 JVM 堆。若建在主索引上，索引里的<b>每个文档</b>都会贡献内存占用，
     * 10 万文档量级足以成为 OOM 的起点。独立索引只存「建议词 + 权重」这一对极小的文档，
     * 体积通常只有主索引的千分之一，且可以通过限制 {@code max_input_length} 进一步压缩。
     *
     * <p>该索引每个类型只需 1 个分片 0 个副本：它极小、可随时整体重建，不需要并行检索能力。
     */
    public String buildSuggestIndexJson(IndexDefinition def) {
        // 与 buildIndexJson 走同一道校验。
        // 这里曾经漏掉过：两条建索引路径的校验口径不一致，意味着「主索引会拒掉的配置，
        // 联想索引照建不误」，而人只会按主索引那一侧的经验去判断配置对不对。
        validate(def);

        ObjectNode root = objectMapper.createObjectNode();

        // 【这里曾经有一个自指别名，已删除 —— 它是联想索引一条都建不出来的原因】
        //
        // 原代码写的是 aliases: {"kuma_article_suggest": {}}，也就是给
        // kuma_article_suggest 这个索引挂一个<b>同名</b>的别名。而 ES 的索引名、别名、
        // 数据流名共享同一个命名空间，要求全局唯一，这个请求会被直接拒掉：
        //
        //   index, alias, and data stream names need to be unique,
        //   but the following duplicates were found
        //   [kuma_article_suggest (alias of [...]) conflicts with index]
        //
        // （8.5.0 曾经放行过，后果是索引与别名都删不掉、节点重启失败，
        //   见 elastic/elasticsearch#91580；本项目用的 8.19 已恢复拒绝。）
        //
        // 于是「建联想索引」这一步在<b>任何环境、任何类型上都必然失败</b>。
        // 更糟的是它失败得毫无存在感：主索引在上一步已经建好并且挂上别名了，
        // 而自动建索引任务的判断条件只看主索引，所以此后它每轮都认为"这个类型已经有索引了"，
        // 再也不重试 —— 一次性的失败就此固化成永久状态（见 docs/08-roadmap.md 第 21 项）。
        //
        // 别名在这里本来也没有用处：查询方是按<b>索引名</b>打的
        // （EsSearchGateway#suggest → def.suggestIndexName()），
        // 而且联想索引不带版本号、不做 reindex 切换，别名能提供的"解耦"它一样也不需要。
        // 结论：删掉，而不是改名 —— 改名只会留下一个永远没人用的别名。
        ObjectNode settings = objectMapper.createObjectNode();
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);
        settings.put("refresh_interval", "30s");
        settings.set("analysis", buildSuggestAnalysis());
        root.set("settings", settings);

        ObjectNode mappings = objectMapper.createObjectNode();
        // 联想索引不接受动态字段：多写一个字段就是多一份堆内存。
        mappings.put("dynamic", "strict");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode suggestField = objectMapper.createObjectNode();
        suggestField.put("type", "completion");
        // 【分析器不是 ik，这是本索引与主索引最重要的一处不同】
        //
        // completion 字段的 FST 是按<b>分析后的 token</b>建的，而前缀匹配要求
        // "用户输入"是"某个已存 token"的前缀。用 ik 会得到一个很别扭的结果：
        // 标题「中国传统器乐曲目-中国百科网」被切成 ["中国","传统","器乐曲目",...]，
        // 于是输入「中国」命中、输入「中国传统」却一条都不出 ——
        // 用户看到的是"联想时灵时不灵"，而原因藏在分词边界的语义里。
        //
        // 换成 关键词分词器 + 小写 之后，一个 input 就是一个 token：
        // 标题的任意<b>前缀</b>都能命中（这正是「输入框补全」应有的语义），
        // 且中文不受 lowercase 影响、拼音串本来就是小写。
        // 代价是没有词中匹配（输入标题中间的一段不会命中），但对下拉补全来说，
        // 「从开头补全」比「猜你想输中间那段」更符合直觉。
        suggestField.put("analyzer", ANALYZER_SUGGEST);
        suggestField.put("search_analyzer", ANALYZER_SUGGEST);
        // 单条 input 的长度上限。默认值 50 对本场景<b>不够用</b>：
        // 拼音 input 的长度约为汉字数的 3 倍（「中国传统器乐曲目-中国百科网」
        // 的全拼是 43 个字母），超长部分会被<b>静默截断</b>，表现为
        // "输入完整拼音反而搜不到"（截断后的串不可能是完整输入的前缀）。
        suggestField.put("max_input_length", 200);
        // 保留位置增量。当前的分析器每个 input 只产出一个 token，此项不起作用；
        // 留着是为了让"改用 ik 之类的分词分析器"时不必再回来改 mapping。
        suggestField.put("preserve_position_increments", true);
        properties.set("suggest", suggestField);

        // 用于展示的建议原文。**不建索引，只放在 _source 里**：
        // completion 建议返回的 text 是"被命中的那个 input"（可能是拼音串），
        // 直接显示给用户就成了 "jiaoyu" 这种样子。下拉框要显示中文标题，
        // 就得从建议选项的 _source 里取这个字段（见 EsSearchGateway#suggest）。
        ObjectNode titleField = objectMapper.createObjectNode();
        titleField.put("type", "keyword");
        titleField.put("index", false);
        properties.set("title", titleField);

        ObjectNode bizIdField = objectMapper.createObjectNode();
        bizIdField.put("type", "keyword");
        properties.set("bizId", bizIdField);

        mappings.set("properties", properties);
        root.set("mappings", mappings);

        return toPrettyJson(root);
    }

    /**
     * 联想索引专属的分析器：整个 input 作为一个 token，并转小写。
     *
     * <p>用 {@code keyword} tokenizer + {@code lowercase} filter 而不是 ES 内置的 {@code keyword}
     * 分析器：内置的那个"是否转小写"属于实现细节（历史上变过），而本索引的查询正确性
     * 依赖"索引期与查询期是同一套规则"，所以把规则显式写出来。
     *
     * <p>两者必须同时用于索引与查询（见 {@link #buildSuggestIndexJson} 里 analyzer 与
     * search_analyzer 都指向它）：只配一个的话，索引期存的是原串、查询期按另一套分析，
     * 前缀就匹配不上了 —— 而这类故障的现象是"联想完全不出东西"，很难回溯到分析器配置。
     */
    private ObjectNode buildSuggestAnalysis() {
        ObjectNode analysis = objectMapper.createObjectNode();
        ObjectNode analyzer = objectMapper.createObjectNode();

        ObjectNode suggestAnalyzer = objectMapper.createObjectNode();
        suggestAnalyzer.put("type", "custom");
        suggestAnalyzer.put("tokenizer", "keyword");
        suggestAnalyzer.putArray("filter").add("lowercase");
        analyzer.set(ANALYZER_SUGGEST, suggestAnalyzer);

        // 灌词用的拼音分析器。**必须定义在联想索引上**：
        // ES 的 _analyze 只有通过 index 参数才能解析出自定义分析器，
        // 而它又必须在某个索引的 settings 里才有定义（集群级的分析器只能改 elasticsearch.yml 重启）。
        // 挂在联想索引上是顺理成章的选择 —— 这个分析器本来就只为联想服务。
        // 参数的理由见 ANALYZER_SUGGEST_PINYIN 的 javadoc。
        //
        // 【这里曾经写错过，记一笔】analyzer 的 tokenizer 字段是<b>名字</b>，不是定义：
        //
        //   "analyzer": { "kuma_suggest_pinyin": { "tokenizer": { "type": "pinyin", ... } } }
        //
        // 上面这种写法是错的 —— 自定义分析器只能<b>按名字引用</b>一个已在
        // analysis.tokenizer 里定义过的 tokenizer，把定义内联进来既不是合法的 ES 语法，
        // 也过不了 Java 客户端的反序列化（它按 CustomAnalyzer 解析，期望这里是个字符串）：
        //
        //   Error deserializing ... CustomAnalyzer: Unexpected JSON event 'START_OBJECT'
        //   instead of '[KEY_NAME, VALUE_STRING, ...]'
        //   (JSON path: settings.analysis.analyzer.kuma_suggest_pinyin.tokenizer)
        //
        // 失败发生在客户端拼请求体时，所以 ES 侧<b>看不到任何日志</b>，只有应用侧一条
        // "创建索引失败"。主索引的 buildSettings 一直是正确写法（customAnalyzer(名字, 过滤器)），
        // 联想索引这条路径是后加的，没有照着抄。
        analyzer.set(ANALYZER_SUGGEST_PINYIN,
                customAnalyzer(TOKENIZER_SUGGEST_PINYIN, List.of("lowercase")));

        ObjectNode tokenizer = objectMapper.createObjectNode();
        tokenizer.set(TOKENIZER_SUGGEST_PINYIN, suggestPinyinTokenizer());
        analysis.set("tokenizer", tokenizer);

        analysis.set("analyzer", analyzer);
        return analysis;
    }

    /**
     * 联想索引上的拼音 tokenizer 定义。
     *
     * <p>与主索引的 {@code pinyinTokenizer} 参数不同，因此是两份而不是复用一份：
     * 主索引要的是"能被检索到"（逐字全拼 + 首字母），联想要的是"能当前缀"
     * （连写全拼 + 首字母，且不要逐字全拼）。两者共享的只有"用同一个插件"这件事，
     * 参数上的差异是刻意的，合并成一个方法反而会把两组诉求搅在一起。
     *
     * <p>{@code lowercase: true} 与主索引保持一致：拼音输出本来就是小写，
     * 这一项实际不起作用，但"不要依赖输出的默认形态"是本文件一贯的写法。
     */
    private ObjectNode suggestPinyinTokenizer() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "pinyin");
        node.put("keep_first_letter", true);
        node.put("keep_full_pinyin", false);
        node.put("keep_joined_full_pinyin", true);
        // keep_none_chinese_in_first_letter 与 keep_none_chinese 必须一起关：
        // 只关注者的话，首字母串里会夹进标题中的拉丁字母（站名、"v1" 之类），
        // 把本应连续的首字母串打断成两段，表现为"输全了反而不出"。
        node.put("keep_none_chinese", false);
        node.put("keep_none_chinese_in_first_letter", false);
        node.put("keep_original", false);
        node.put("limit_first_letter_length", 64);
        node.put("lowercase", true);
        node.put("remove_duplicated_term", true);
        return node;
    }

    /**
     * 只生成 mappings 部分，用于对<b>已存在</b>的索引做 {@code PUT /{index}/_mapping} 演进。
     *
     * <p>注意 ES 的 mapping 只能<b>新增</b>字段，不能修改已有字段的类型。要改类型必须重建索引，
     * 这正是 {@code DynamicIndexManager} 提供 reindex 能力的原因。
     */
    public ObjectNode buildMapping(IndexDefinition def) {
        ObjectNode mappings = objectMapper.createObjectNode();

        // 刻意关闭动态映射：配置表与写入数据可能短暂不一致，
        // 用 "false" 既能忽略未知字段（不因脏字段让写入失败），
        // 又能避免 ES 自作主张创建出不符合规范的字段类型 —— 这是 ES 项目里最常见的隐性技术债。
        mappings.put("dynamic", "false");
        // 关闭日期自动探测：否则 "2024-01-01" 这类商品编码会被猜成 date 类型，后续无法作为 keyword 聚合。
        mappings.put("date_detection", false);

        ObjectNode properties = objectMapper.createObjectNode();
        if (def.getFields() != null) {
            for (FieldConfig field : def.getFields()) {
                properties.set(field.getName(), buildFieldProperty(field));
            }
        }
        mappings.set("properties", properties);
        mappings.set("_meta", buildMeta(def));
        return mappings;
    }

    /**
     * 索引元数据（{@code _meta}）：只放"给人看"的说明，不参与 ES 的任何行为。
     *
     * <h3>为什么是 {@code _meta} 而不是 {@code settings}</h3>
     * 这里原本写的是 {@code settings.kuma_ik_dict_hint.*}。那是一个<b>硬错误</b>：
     * ES 在校验 index settings 时只认自己已知的设置项，任何多余的键都会让整个建索引请求
     * 以 {@code illegal_argument_exception: unknown setting [index.kuma_ik_dict_hint.stop_dict]}
     * 失败 —— 也就是说，<b>这个类型的索引一次都建不出来</b>。
     * 它与"设置值不合法"不同，是"键本身不被接受"，因此连重试、换值都无用。
     *
     * <p>而 {@code _meta} 是 ES 唯一允许存放任意 JSON 的地方：写进去、随 mapping 一起读回来、
     * 不产生任何副作用。凡是"想留在索引上给运维看"的东西都该放这里。
     *
     * <h3>这段说明本身为什么值得留</h3>
     * IK 的词典路径是在<b>节点</b>上配置的（{@code plugins/ik/config/IKAnalyzer.cfg.xml}），
     * 索引侧无法指定，也无法从索引侧读出"当前用的是哪个词典文件"。
     * 把它写进 {@code _meta} 之后，{@code GET /kuma_article/_mapping} 就能回答
     * 「这个索引是按哪份词典建的」—— 而这正是排查"为什么这个词分不出来"时第一个要问的问题。
     */
    private ObjectNode buildMeta(IndexDefinition def) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("typeCode", def.getTypeCode());
        meta.put("managedBy", "KumaSearch MappingGenerator");
        meta.put("version", def.getVersion() == null ? 1 : def.getVersion());

        ObjectNode ikDict = objectMapper.createObjectNode();
        ikDict.put("ext_dict", IK_EXT_DICT);
        ikDict.put("stop_dict", IK_STOP_DICT);
        ikDict.put("note", "词典路径配置在 ES 节点的 plugins/ik/config/IKAnalyzer.cfg.xml，"
                + "此处仅记录预期值，用于核对；索引侧无法设置它");
        meta.set("ikDictHint", ikDict);

        meta.put("synonymFile", SYNONYM_FILE);
        return meta;
    }

    // ==================================================================================
    //  字段级映射
    // ==================================================================================

    private ObjectNode buildFieldProperty(FieldConfig field) {
        ObjectNode node = objectMapper.createObjectNode();
        FieldType type = field.getType() == null ? FieldType.TEXT : field.getType();

        switch (type) {
            case TEXT -> buildTextProperty(field, node);
            case KEYWORD -> {
                node.put("type", "keyword");
                node.put("ignore_above", 1024);
                attachPinyinSubFields(field, node);
            }
            case INTEGER -> node.put("type", "integer");
            case LONG -> node.put("type", "long");
            case DOUBLE -> node.put("type", "double");
            case BOOLEAN -> node.put("type", "boolean");
            case DATE -> {
                node.put("type", "date");
                node.put("format", field.getFormat() != null
                        ? field.getFormat()
                        : DEFAULT_DATE_FORMAT);
            }
            case GEO_POINT -> node.put("type", "geo_point");
            case NESTED -> {
                node.put("type", "nested");
                // nested 内部字段由 extra JSON 承载，此处只声明容器；具体子字段在类型配置里以 "父.子" 命名展开。
                node.putObject("properties");
            }
        }

        // facet 聚合依赖 doc_values。text 类型默认关闭 doc_values，
        // 因此对需要聚合的 text 字段，实际聚合走的是它自动生成的 .keyword 子字段。
        if (Boolean.TRUE.equals(field.getMultiValued()) && type != FieldType.NESTED) {
            log.debug("字段 {} 声明为多值，ES 天然支持数组，无需额外 mapping 配置", field.getName());
        }
        return node;
    }

    /**
     * TEXT 字段：IK 主字段 + keyword 子字段 + pinyin 子字段。
     *
     * <p>子字段的价值在于「同一份原文，用不同方式切分，各查各的」——
     * 想让「手机」既能被中文分词命中、又能被「shouji」「sj」命中、还能被精确聚合，
     * 就必须是三个子字段，而不是一个字段上挂三个 analyzer。
     */
    private void buildTextProperty(FieldConfig field, ObjectNode node) {
        node.put("type", "text");
        node.put("analyzer", field.getAnalyzer() != null ? field.getAnalyzer() : ANALYZER_IK_MAX_WORD);
        node.put("search_analyzer", field.getSearchAnalyzer() != null
                ? field.getSearchAnalyzer() : ANALYZER_SEARCH);

        ObjectNode fields = objectMapper.createObjectNode();

        // keyword 子字段：支撑「按标题精确聚合/排序」，text 字段本身无法做这些。
        ObjectNode keywordSub = objectMapper.createObjectNode();
        keywordSub.put("type", "keyword");
        keywordSub.put("ignore_above", 256);
        fields.set("keyword", keywordSub);

        node.set("fields", fields);

        // 拼音子字段按需追加到同一个 fields 节点上。
        attachPinyinSubFields(field, node);
    }

    /**
     * 按需为字段追加拼音子字段。
     *
     * <p>只有显式 {@code pinyin=true} 的字段才生成 —— 拼音子字段会让倒排索引体积增加约 40%，
     * 全量开启是典型的用存储换不必要的便利。
     */
    private void attachPinyinSubFields(FieldConfig field, ObjectNode node) {
        if (!Boolean.TRUE.equals(field.getPinyin())) {
            return;
        }
        ObjectNode fields = (ObjectNode) node.get("fields");
        if (fields == null) {
            fields = objectMapper.createObjectNode();
            node.set("fields", fields);
        }

        ObjectNode fullPinyin = objectMapper.createObjectNode();
        fullPinyin.put("type", "text");
        fullPinyin.put("analyzer", ANALYZER_PINYIN);
        fullPinyin.put("search_analyzer", ANALYZER_PINYIN);
        fields.set("pinyin", fullPinyin);

        ObjectNode firstLetter = objectMapper.createObjectNode();
        firstLetter.put("type", "text");
        firstLetter.put("analyzer", ANALYZER_PINYIN_FPY);
        firstLetter.put("search_analyzer", ANALYZER_PINYIN_FPY);
        fields.set("fpy", firstLetter);
    }

    // ==================================================================================
    //  settings
    // ==================================================================================

    private ObjectNode buildSettings(IndexDefinition def) {
        ObjectNode settings = objectMapper.createObjectNode();
        settings.put("number_of_shards", def.getShards() != null ? def.getShards() : DEFAULT_SHARDS);
        settings.put("number_of_replicas", def.getReplicas() != null ? def.getReplicas() : DEFAULT_REPLICAS);

        // 保持默认 1s 刷新。设成 0（每写必刷）会让写入吞吐断崖下跌；
        // 设成 30s 又会让「刚录入的内容搜不到」变成客服问题。1s 是这两者的标准平衡点。
        settings.put("refresh_interval", "1s");

        // 与查询期 max-result-window 保持一致，超过该窗口必须走 search_after。
        settings.put("max_result_window", 10000);

        ObjectNode analysis = objectMapper.createObjectNode();

        ObjectNode filter = objectMapper.createObjectNode();
        ObjectNode synonym = objectMapper.createObjectNode();
        synonym.put("type", "synonym_graph");
        synonym.put("synonyms_path", SYNONYM_FILE);
        // updateable=true 允许用 _reload_search_analyzers API 热更新同义词，无需重启节点。
        synonym.put("updateable", true);
        filter.set("kuma_synonym_graph", synonym);
        analysis.set("filter", filter);

        ObjectNode analyzer = objectMapper.createObjectNode();

        // 查询期分析器：ik_smart 粗粒度切分（"中华人民共和国" → "中华人民共和国"，而不是拆成 4 个词），
        // 再叠加同义词扩展。"手机" 会被扩展成 "手机,移动电话,handset" 的同义词图。
        analyzer.set(ANALYZER_SEARCH, customAnalyzer("ik_smart",
                List.of("lowercase", "kuma_synonym_graph")));

        analyzer.set(ANALYZER_PINYIN, customAnalyzer("kuma_pinyin_tokenizer", List.of("lowercase")));
        analyzer.set(ANALYZER_PINYIN_FPY, customAnalyzer("kuma_pinyin_fpy_tokenizer", List.of("lowercase")));

        analysis.set("analyzer", analyzer);

        ObjectNode tokenizer = objectMapper.createObjectNode();
        tokenizer.set("kuma_pinyin_tokenizer", pinyinTokenizer(true, true));
        tokenizer.set("kuma_pinyin_fpy_tokenizer", pinyinTokenizer(true, false));
        analysis.set("tokenizer", tokenizer);

        settings.set("analysis", analysis);

        return settings;
    }

    private ObjectNode customAnalyzer(String tokenizer, List<String> filters) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "custom");
        node.put("tokenizer", tokenizer);
        ArrayNode filterArray = node.putArray("filter");
        filters.forEach(filterArray::add);
        return node;
    }

    /**
     * 拼音 tokenizer 配置。
     *
     * @param keepFirstLetter 是否保留首字母（「手机」→「sj」）
     * @param keepFullPinyin  是否保留全拼（「手机」→「shouji」）
     */
    private ObjectNode pinyinTokenizer(boolean keepFirstLetter, boolean keepFullPinyin) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "pinyin");
        node.put("keep_first_letter", keepFirstLetter);
        // 不保留「分开的首字母」：它会为「中华」额外产出 "z h"，制造大量短噪声 token。
        node.put("keep_separate_first_letter", false);
        node.put("keep_full_pinyin", keepFullPinyin);
        node.put("keep_joined_full_pinyin", false);
        // keep_original=false：原文由主字段负责召回，拼音字段重复存一份纯属浪费。
        node.put("keep_original", false);
        node.put("limit_first_letter_length", 16);
        node.put("lowercase", true);
        node.put("remove_duplicated_term", true);
        return node;
    }

    private ObjectNode buildAliases(IndexDefinition def) {
        ObjectNode aliases = objectMapper.createObjectNode();
        ObjectNode aliasBody = objectMapper.createObjectNode();
        // is_write_index=true 让别名具备写入归属语义。
        // 这样在「新索引灌数据、旧索引仍在服务」的窗口期内，
        // 写请求仍然只落到一个索引上，不会出现双写。
        aliasBody.put("is_write_index", true);
        aliases.set(def.getAlias(), aliasBody);
        return aliases;
    }

    // ==================================================================================
    //  工具
    // ==================================================================================

    private void validate(IndexDefinition def) {
        if (def == null || def.getAlias() == null || def.getAlias().isBlank()) {
            throw BizException.of(ResultCode.INDEX_TYPE_CODE_EXISTS, "索引别名不能为空");
        }
        if (def.getVersion() == null || def.getVersion() < 1) {
            throw BizException.of(ResultCode.PARAM_INVALID, "索引版本号必须 >= 1");
        }
        if (def.getFields() == null || def.getFields().isEmpty()) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "类型 " + def.getTypeCode() + " 未配置任何字段，拒绝创建索引");
        }
        if (def.getPkField() == null || def.getPkField().isBlank()) {
            throw BizException.of(ResultCode.PARAM_INVALID, "必须指定业务主键字段（用作 ES 的 _id）");
        }
    }

    /** 供后台「mapping 预览」接口使用：返回格式化后的 JSON，便于管理员核对。 */
    public String previewMapping(IndexDefinition def) {
        return toPrettyJson(buildMapping(def));
    }

    /** 解析管理员粘贴的 field_config JSON，失败时给出可定位的错误信息而不是裸的 NPE。 */
    public List<FieldConfig> parseFieldConfig(String fieldConfigJson) {
        if (fieldConfigJson == null || fieldConfigJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(fieldConfigJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, FieldConfig.class));
        } catch (JsonProcessingException e) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "field_config 不是合法的字段配置数组: " + e.getOriginalMessage());
        }
    }

    /** 解析 search_config JSON（默认检索字段、高亮字段、默认排序等）。 */
    public JsonNode parseSearchConfig(String searchConfigJson) {
        if (searchConfigJson == null || searchConfigJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(searchConfigJson);
        } catch (JsonProcessingException e) {
            throw BizException.of(ResultCode.PARAM_INVALID,
                    "search_config 不是合法 JSON: " + e.getOriginalMessage());
        }
    }

    private String toPrettyJson(ObjectNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // 由 ObjectNode 序列化到字符串不可能失败；真失败了说明 ObjectMapper 被外部破坏，属于程序缺陷。
            throw BizException.of(ResultCode.SYSTEM_ERROR, "mapping 序列化失败", e);
        }
    }
}
