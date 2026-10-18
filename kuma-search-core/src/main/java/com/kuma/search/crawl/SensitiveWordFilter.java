package com.kuma.search.crawl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.repository.entity.SearchDict;
import com.kuma.search.repository.mapper.SearchDictMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 敏感词过滤：Aho-Corasick 自动机，一次遍历匹配全部词。
 *
 * <p>词库复用 {@code t_search_dict} 表（{@code dict_type='SENSITIVE'}），<b>不新建词库表</b>。
 * 这张表已经是「按 {@code dict_type} 区分的多类词表」（主词/停用词/同义词），
 * 敏感词只是第四类 —— 与 {@code t_search_content} 承载多种内容类型是同一套哲学。
 * 新建一张结构相同的表，只会让「词库在哪」这个问题多一个答案。
 *
 * <h3>⚠️ 这套机制的边界，不要误读</h3>
 * <b>它只能挡住「词库里已有的」内容。</b>
 * <ul>
 *   <li>同音字、拆字、拼音、火星文、图片文字 —— 一个都挡不住；</li>
 *   <li>换一种表达方式的违规内容（不含任何词库里的字）—— 挡不住；</li>
 *   <li>词库本身的覆盖度取决于谁来维护、多久更新一次（见 {@code docs/08-roadmap.md}
 *       的「敏感词库的持续维护机制」待办）。</li>
 * </ul>
 * 因此它是<b>第一道闸而不是唯一一道</b>：人工审核队列（{@code CrawlAuditService}）
 * 与举报入口是必需的补充。任何把这套机制描述成「已实现内容安全」的说法都是不实的。
 *
 * <h3>为什么用 Aho-Corasick</h3>
 * 朴素做法是「对每个词扫一遍正文」，词库上千、正文上万字时就是千万级字符比较，
 * 乘以每页的采集量会直接把 CPU 吃满。Aho-Corasick 把复杂度降到与正文长度成正比、
 * 与词库规模无关（一次遍历同时匹配所有词），代价是建一次自动机。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordFilter {

    /** 与 {@code DictType.SENSITIVE} 对应。写死字面量而非常量引用，是为了让 core 模块不依赖 admin 侧的枚举。 */
    private static final String DICT_TYPE_SENSITIVE = "SENSITIVE";

    /** 启用状态的词条。与 {@code t_search_dict.status} 同义。 */
    private static final int DICT_STATUS_ENABLED = 1;

    /**
     * 单个词条允许的最大长度。
     *
     * <p>超过它的词直接忽略并告警：自动机的内存占用与「最长词长度 × 节点数」相关，
     * 一条几万字的手滑粘贴会在建树时吃掉大量内存。限制它比事后排查 OOM 便宜得多。
     */
    private static final int MAX_WORD_LENGTH = 64;

    /**
     * 一次匹配最多返回多少个命中词。
     *
     * <p>有的页面（尤其是采集到的列表页）会重复出现同一个词几十次。
     * 全部收集既没必要（审核只需知道「命中了哪些词」）又会撑爆
     * {@code t_crawl_audit_log.matched_words} 的长度。
     */
    private static final int MAX_MATCHED_WORDS = 20;

    private final SearchDictMapper searchDictMapper;

    /**
     * 当前生效的自动机。
     *
     * <p>用 {@code AtomicReference} 做整体替换而不是给节点加锁：重新加载时构造成一棵新树、
     * 一次性切换引用。匹配线程要么看到旧树、要么看到新树，永远不会看到「建了一半的树」
     * —— 后者会导致随机漏匹配，而且这种 bug 只在重载的那一瞬间出现，几乎不可能复现。
     *
     * <p>声明为普通字段（而非 {@code final}）是刻意的：它带初始值，
     * 不参与 Lombok 生成的构造器，加 {@code final} 只会让某些编译器/IDE
     * 对「带初始化器的 final 字段」给出误报，换不来任何保护。
     */
    private AtomicReference<Automaton> automatonRef = new AtomicReference<>(Automaton.empty());

    @PostConstruct
    void init() {
        reload();
    }

    /**
     * 定期重载词库。词库改完不必重启进程。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：重载要查库，查库慢的时候
     * {@code fixedRate} 会让任务堆积重叠执行，而重载本身是幂等的、堆积毫无意义。
     */
    @Scheduled(fixedDelayString = "${kuma.crawl.sensitive-reload-seconds:300}000")
    public void scheduledReload() {
        reload();
    }

    /**
     * 重新加载词库并重建自动机。
     *
     * <p>失败时<b>保留旧的自动机</b>，不清空。空自动机会让所有内容都「没有命中敏感词」，
     * 也就是静默地全部放行 —— 一次数据库抖动就变成一次内容安全失效，这个代价太大。
     */
    public void reload() {
        try {
            LambdaQueryWrapper<SearchDict> wrapper = new LambdaQueryWrapper<SearchDict>()
                    .eq(SearchDict::getDictType, DICT_TYPE_SENSITIVE)
                    .eq(SearchDict::getStatus, DICT_STATUS_ENABLED);
            List<SearchDict> words = searchDictMapper.selectList(wrapper);

            Automaton automaton = Automaton.build(filterWords(words));
            automatonRef.set(automaton);
            log.info("敏感词库已加载: 词条数={} 跳过(过长)={}", automaton.wordCount,
                    words.size() - automaton.wordCount);
        } catch (RuntimeException e) {
            // 保留旧自动机，见方法注释
            log.error("敏感词库加载失败，继续使用上一次的词库: {}", e.getMessage(), e);
        }
    }

    /** 过滤掉长度非法或为空的词条。 */
    private List<String> filterWords(List<SearchDict> dicts) {
        List<String> words = new ArrayList<>();
        for (SearchDict dict : dicts) {
            String word = dict.getWord() == null ? null : dict.getWord().trim();
            if (word == null || word.isEmpty()) {
                continue;
            }
            if (word.length() > MAX_WORD_LENGTH) {
                log.warn("敏感词过长已跳过({} 字符, 上限 {})，请确认是否误录入: id={}",
                        word.length(), MAX_WORD_LENGTH, dict.getId());
                continue;
            }
            words.add(word);
        }
        return words;
    }

    /**
     * 在文本中找出所有命中的敏感词。
     *
     * <p>大小写不敏感（英文词库需要），因此比较前统一转小写 ——
     * 中文不受影响，中文只有全角/半角的差别，那属于词库录入时该处理的事。
     *
     * @param text 待检测文本；为 null/空时返回空列表
     * @return 命中的词（去重、保持首次命中的顺序）；<b>空列表表示「词库中未命中」，
     *         不等于「内容安全」</b>
     */
    public List<String> match(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return automatonRef.get().match(text.toLowerCase(java.util.Locale.ROOT));
    }

    /** 是否存在命中。审核动作的一个便捷判定。 */
    public boolean contains(String text) {
        return !match(text).isEmpty();
    }

    /** 当前词库规模，供后台展示与健康检查。 */
    public int wordCount() {
        return automatonRef.get().wordCount;
    }

    /**
     * Aho-Corasick 自动机。
     *
     * <p>实现要点（这三条是它区别于朴素 Trie 的全部）：</p>
     * <ol>
     *   <li><b>失败指针</b>（{@code fail}）—— 匹配失配时跳到「最长后缀同时是某个前缀」
     *       的节点，而不是回到根。没有它，复杂度就退化成朴素算法。</li>
     *   <li><b>转移补全</b> —— 建树完成后把每个节点缺失的子转移补成
     *       「沿失败指针继续找」。这样匹配循环里没有 while，是纯 O(n)。</li>
     *   <li><b>输出合并</b> —— 一个节点可能同时是多个词（例如 {@code fail} 链上还挂着短词），
     *       必须把失败链上的输出一并继承，否则会漏掉「长度是别的词后缀」的那些词。</li>
     * </ol>
     */
    private static final class Automaton {

        /** 根节点索引恒为 0。 */
        private static final int ROOT = 0;

        private final List<Map<Character, Integer>> transitions;
        private final int[] fail;
        /** 每个节点终止的词（可能多个）。索引与节点一一对应。 */
        private final List<List<String>> outputs;
        private final int wordCount;

        private Automaton(List<Map<Character, Integer>> transitions, int[] fail,
                          List<List<String>> outputs, int wordCount) {
            this.transitions = transitions;
            this.fail = fail;
            this.outputs = outputs;
            this.wordCount = wordCount;
        }

        static Automaton empty() {
            List<Map<Character, Integer>> t = new ArrayList<>();
            t.add(new HashMap<>());
            return new Automaton(t, new int[]{0}, new ArrayList<>(List.of(List.of())), 0);
        }

        /**
         * 建树。
         *
         * <p>词表为空时返回空自动机 —— 此时匹配永远返回空，
         * 与「词库还没配」的实际状态一致。调用方不应把它理解为「内容安全」。
         */
        static Automaton build(List<String> words) {
            List<Map<Character, Integer>> transitions = new ArrayList<>();
            transitions.add(new HashMap<>());
            List<List<String>> outputs = new ArrayList<>();
            outputs.add(new ArrayList<>());

            int count = 0;
            for (String word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                int node = ROOT;
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    Integer next = transitions.get(node).get(c);
                    if (next == null) {
                        next = transitions.size();
                        transitions.get(node).put(c, next);
                        transitions.add(new HashMap<>());
                        outputs.add(new ArrayList<>());
                    }
                    node = next;
                }
                if (!outputs.get(node).contains(word)) {
                    outputs.get(node).add(word);
                    count++;
                }
            }

            int size = transitions.size();
            int[] fail = new int[size];
            Deque<Integer> queue = new ArrayDeque<>();

            // 第一层：失败指针指向根
            for (Integer child : transitions.get(ROOT).values()) {
                fail[child] = ROOT;
                queue.add(child);
            }

            // BFS 逐层构造失败指针与补全转移
            while (!queue.isEmpty()) {
                int current = queue.poll();
                for (Map.Entry<Character, Integer> entry : transitions.get(current).entrySet()) {
                    char c = entry.getKey();
                    int child = entry.getValue();

                    // 沿失败链找「也有 c 转移」的节点
                    int f = fail[current];
                    while (f != ROOT && !transitions.get(f).containsKey(c)) {
                        f = fail[f];
                    }
                    Integer candidate = transitions.get(f).get(c);
                    fail[child] = (candidate != null && candidate != child) ? candidate : ROOT;

                    // 输出合并：继承失败链上的输出，否则会漏掉「是别的词后缀」的短词
                    if (!outputs.get(fail[child]).isEmpty()) {
                        outputs.get(child).addAll(outputs.get(fail[child]));
                    }
                    queue.add(child);
                }
            }

            return new Automaton(transitions, fail, outputs, count);
        }

        /**
         * 在文本中匹配。
         *
         * <p>转移在这里<b>就地补全</b>（把「失配后该去哪」写进转移表），
         * 匹配循环因此没有内层 while，是严格的 O(n)。这与建树阶段分离，
         * 是为了不在建树时改动正在被 BFS 遍历的集合 —— 边遍历边改会
         * 触发 {@code ConcurrentModificationException}。
         */
        List<String> match(String text) {
            if (wordCount == 0 || text.isEmpty()) {
                return List.of();
            }
            List<String> matched = new ArrayList<>();
            int node = ROOT;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                while (node != ROOT && !transitions.get(node).containsKey(c)) {
                    node = fail[node];
                }
                Integer next = transitions.get(node).get(c);
                node = next != null ? next : ROOT;

                for (String word : outputs.get(node)) {
                    if (!matched.contains(word)) {
                        matched.add(word);
                        if (matched.size() >= MAX_MATCHED_WORDS) {
                            return matched;
                        }
                    }
                }
            }
            return matched;
        }
    }
}
