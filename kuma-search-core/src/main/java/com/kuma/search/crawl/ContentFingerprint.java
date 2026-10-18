package com.kuma.search.crawl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内容指纹：判定两段正文「是不是同一份」。
 *
 * <p>这里提供两种粒度，它们的用途<b>完全不同，不能互相替代</b>：
 *
 * <table border="1">
 *   <caption>两种指纹的分工</caption>
 *   <tr><th>方法</th><th>算法</th><th>回答的问题</th><th>误判</th></tr>
 *   <tr>
 *     <td>{@link #sha256(String)}</td><td>SHA-256</td>
 *     <td>「这一页和上次抓的是不是<b>逐字节</b>一样？」</td>
 *     <td>无（就是判等）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #simHash(String)}</td><td>SimHash（64 位）</td>
 *     <td>「这一页和另一页是不是<b>近似</b>重复？」</td>
 *     <td>有，靠汉明距离阈值控制</td>
 *   </tr>
 * </table>
 *
 * <p><b>SHA-256 用于省工作量</b>：存进 {@code t_crawl_url.content_hash}，重抓时若未变
 * 就跳过写 ES 与重新送审。这个判定必须是精确的 —— 一次误判会让真实的内容变更丢失，
 * 而且不会有任何报错，只表现为「搜索结果没更新」。
 *
 * <p><b>SimHash 用于剔除重复内容</b>：同一篇稿子被多个站点转载、列表页里嵌了几条
 * 相同的推荐语，这些内容 hash 都不同，但送进搜索结果的是一堆近乎一样的文档。
 * 近重复判定用来决定「这一页要不要进审核队列」。
 *
 * <p>⚠️ <b>SimHash 的阈值不能当常量拍死。</b>它对短文本极不稳定：几百字的页面
 * 里改一个词就可能让汉明距离跳好几位，而长页面改一大段也跳不了多少。
 * 因此 {@link #isNearDuplicate(String, String)} 用的是「按长度分档」的阈值，
 * 且总长度低于 {@link #MIN_LENGTH_FOR_SIMHASH} 时直接放弃相似判定、
 * 退化为精确比较 —— 对短文谎报「重复」的代价（内容永远进不了搜索）比漏报大得多。
 */
@Slf4j
@Component
public class ContentFingerprint {

    /**
     * 低于这个字符数就不做 SimHash 相似判定。
     *
     * <p>短文本的特征太少，SimHash 会给出「任何两段中文短句都相似」的结果。
     * 与其调阈值，不如承认这个算法在这里不适用。
     */
    public static final int MIN_LENGTH_FOR_SIMHASH = 200;

    /** SimHash 的位数。64 位是精度与 long 存储的平衡点。 */
    private static final int HASH_BITS = 64;

    /**
     * 汉明距离阈值按正文长度分档。
     *
     * <p>为什么越长的文本允许越大的距离：SimHash 的偏差随特征数增加而收敛，
     * 长文本的「正常波动」本来就更大。用同一个数字卡所有长度，
     * 要么对短文太松（把不同内容判成重复），要么对长文太紧（漏掉真正的转载）。
     *
     * <p>初始值刻意保守（偏松），因为这里误判的后果是内容被静默丢弃。
     * 上线后应结合真实数据的统计再调，调的依据是「被判重复的内容里有多少是真的重复」。
     */
    private static final Map<LengthBucket, Integer> DISTANCE_THRESHOLDS = new EnumMap<>(LengthBucket.class);

    static {
        DISTANCE_THRESHOLDS.put(LengthBucket.SHORT, 3);
        DISTANCE_THRESHOLDS.put(LengthBucket.MEDIUM, 6);
        DISTANCE_THRESHOLDS.put(LengthBucket.LONG, 10);
    }

    /** 正文长度分档，见 {@link #DISTANCE_THRESHOLDS}。 */
    public enum LengthBucket {
        /** 200 ~ 1000 字。 */
        SHORT,
        /** 1000 ~ 5000 字。 */
        MEDIUM,
        /** 5000 字以上。 */
        LONG
    }

    /**
     * 精确指纹：正文的 SHA-256（64 位小写十六进制），对应 {@code t_crawl_url.content_hash}。
     *
     * <p>入参为 null 或空白时返回 null，调用方据此判断「没抽到正文」，不要写进数据库
     * —— 写 null 会让「未变判定」永远为 false，导致每一轮重抓都当作内容变更重新送审。
     *
     * <p><b>计算前会做一次轻量规范化</b>（见 {@link #normalizeText}）：否则页面里
     * 一个广告位的随机数字、一个「阅读量 1234」都会让 hash 每次都变，
     * 「内容未变则跳过」这个优化就完全失效了。
     */
    public String sha256(String content) {
        String normalized = normalizeText(content);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /**
     * 相似指纹：64 位 SimHash，以十六进制字符串返回（便于落库与对比）。
     *
     * <p>正文过短（低于 {@link #MIN_LENGTH_FOR_SIMHASH}）时返回空，
     * 调用方必须把「空」理解为「不支持相似判定」而不是「与任何内容都不相似」。
     *
     * @return 16 位小写十六进制；文本过短或为空时返回 {@link Optional#empty()}
     */
    public Optional<String> simHash(String content) {
        String normalized = normalizeText(content);
        if (normalized.length() < MIN_LENGTH_FOR_SIMHASH) {
            return Optional.empty();
        }
        long hash = computeSimHash(normalized);
        return Optional.of(String.format("%016x", hash));
    }

    /**
     * 判定两段正文是否近似重复。
     *
     * <p>任一方的 SimHash 不可用（太短）时，<b>退化为精确比较</b>而不是直接返回 false。
     * 这样短内容之间仍然能被去重，只是用的是更严格的标准。
     */
    public boolean isNearDuplicate(String contentA, String contentB) {
        Optional<String> hashA = simHash(contentA);
        Optional<String> hashB = simHash(contentB);
        if (hashA.isEmpty() || hashB.isEmpty()) {
            String shaA = sha256(contentA);
            String shaB = sha256(contentB);
            if (shaA == null || shaB == null) {
                return false;
            }
            return shaA.equals(shaB);
        }
        return hammingDistance(hashA.get(), hashB.get()) <= thresholdFor(contentA.length());
    }

    /**
     * 按正文长度取汉明距离阈值。
     *
     * <p>越界（长度不足 {@link #MIN_LENGTH_FOR_SIMHASH}）时返回 SHORT 档：
     * 这个方法只会在已经走完 SimHash 分支后调用，取最严的档位即可。
     */
    public int thresholdFor(int length) {
        LengthBucket bucket = length < 1000 ? LengthBucket.SHORT
                : length < 5000 ? LengthBucket.MEDIUM
                : LengthBucket.LONG;
        return DISTANCE_THRESHOLDS.getOrDefault(bucket, DISTANCE_THRESHOLDS.get(LengthBucket.MEDIUM));
    }

    /**
     * 计算两个十六进制 SimHash 之间的汉明距离（不同的二进制位个数）。
     *
     * @return 距离；任一入参格式非法时返回 {@link Integer#MAX_VALUE}，
     *         即「视作完全不同」—— 宁可多送一次审核，也不要因为脏数据把内容误判成重复
     */
    public int hammingDistance(String hexA, String hexB) {
        try {
            long a = Long.parseUnsignedLong(hexA, 16);
            long b = Long.parseUnsignedLong(hexB, 16);
            return Long.bitCount(a ^ b);
        } catch (NumberFormatException e) {
            log.warn("SimHash 格式非法，将视为完全不同: a={} b={}", hexA, hexB);
            return Integer.MAX_VALUE;
        }
    }

    /**
     * SimHash 主体：把文本切成特征（字符二元组），为每个特征算 64 位哈希，
     * 按位投票 —— 该位为 1 则 +1，为 0 则 -1，最后取符号位。
     *
     * <p><b>用字符二元组而不是分词</b>是有意的：中文分词需要词典与模型，
     * 引入的依赖和不确定性（词典不同结果就不同）都太大，而二元组对中文的区分度
     * 已经足够支撑「这页是不是那页的转载」这个判定。代价是对**打乱语序**的改写不敏感，
     * 但这类改写在实践中很罕见。
     */
    private long computeSimHash(String text) {
        int[] vector = new int[HASH_BITS];
        int count = 0;
        for (int i = 0; i + 1 < text.length(); i++) {
            String gram = text.substring(i, i + 2);
            long gramHash = fnv1a64(gram);
            for (int bit = 0; bit < HASH_BITS; bit++) {
                if (((gramHash >>> bit) & 1L) == 1L) {
                    vector[bit]++;
                } else {
                    vector[bit]--;
                }
            }
            count++;
        }
        if (count == 0) {
            return 0L;
        }
        long result = 0L;
        for (int bit = 0; bit < HASH_BITS; bit++) {
            if (vector[bit] > 0) {
                result |= (1L << bit);
            }
        }
        return result;
    }

    /**
     * FNV-1a 64 位哈希。
     *
     * <p>选它而不是 {@code String.hashCode()}：后者只有 32 位且分布很差，
     * 用在 SimHash 这类需要每一位都均匀的场合会引入系统性偏差。
     */
    private long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * 计算指纹前的文本规范化：去掉所有空白字符。
     *
     * <p>只做这一件事，且必须做。页面的 HTML 排版会在重抓时变（多一个换行、
     * 缩进不同），若指纹对这些敏感，「内容未变则跳过」就形同虚设 ——
     * 每轮重抓都会认为内容变了，重新写 ES、重新送审。
     *
     * <p><b>刻意不做的事</b>：不转小写、不去标点。中文里大小写无关紧要，
     * 但标点是内容的一部分，去掉它会让「不加标点的改写」被判成完全相同的页面。
     * 这种粗粒度的相似交给 SimHash 处理，不要混进精确指纹。
     */
    private String normalizeText(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
