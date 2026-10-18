package com.kuma.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目自定义配置，统一挂在 {@code kuma.*} 前缀下。
 *
 * <p>把所有可调项收敛到一个类，是为了让「部署时到底有哪些旋钮」这个问题有唯一答案 ——
 * 散落在各个 {@code @Value} 里的配置项在排查线上问题时几乎不可能被穷举。
 *
 * <p>对应的 {@code application.yml} 片段与「哪些项必须走环境变量」见
 * {@code docs/07-deployment.md} 的配置项清单。
 */
@Data
@Component
@ConfigurationProperties(prefix = "kuma")
public class KumaSearchProperties {

    /** Elasticsearch 连接与客户端行为，对应 {@code kuma.elasticsearch.*}。 */
    private Elasticsearch elasticsearch = new Elasticsearch();
    private Jwt jwt = new Jwt();
    private Auth auth = new Auth();
    private Cache cache = new Cache();
    private Search search = new Search();
    private Sync sync = new Sync();
    private Mq mq = new Mq();
    /** 匿名接口限流，对应 {@code kuma.rate-limit.*}。 */
    private RateLimit rateLimit = new RateLimit();
    /** 人机校验（图形验证码），对应 {@code kuma.captcha.*}。 */
    private Captcha captcha = new Captcha();
    /** 网页采集，对应 {@code kuma.crawl.*}。 */
    private Crawl crawl = new Crawl();
    /** 联想（下拉建议）的灌词策略，对应 {@code kuma.suggest.*}。 */
    private Suggest suggest = new Suggest();

    /** Elasticsearch 连接与客户端行为。 */
    @Data
    public static class Elasticsearch {
        /** 集群地址列表。单机部署时只有一个；集群化时填全部协调节点。 */
        private List<String> uris = List.of("http://127.0.0.1:9200");
        private String username;
        private String password;
        /** 连接超时（毫秒）。 */
        private int connectTimeout = 3000;
        /** 读超时（毫秒）。搜索接口的 P95 目标依赖它兜底，不宜过大。 */
        private int socketTimeout = 10000;
        /** 从节点失败中恢复的等待时间（毫秒）。 */
        private int failureNodeTimeout = 30000;
        /** 单次查询最大返回条数，超过则拒绝而不是让 ES 去分配大数组。 */
        private int maxPageSize = 100;
        /** 浅分页窗口上限，超过该窗口强制走 search_after，与索引的 max_result_window 保持一致。 */
        private int maxResultWindow = 10000;
        /** 批量写入的批大小。 */
        private int bulkBatchSize = 500;
    }

    /** JWT 认证配置。 */
    @Data
    public static class Jwt {
        /**
         * 签名密钥。<b>必须通过环境变量 {@code KUMA_JWT_SECRET} 注入，禁止提交到代码库</b>，
         * 且长度至少 32 字节（HS256 要求）。
         */
        private String secret;
        /** access token 有效期（秒），默认 2 小时。 */
        private long accessTokenTtl = 7200;
        /** refresh token 有效期（秒），默认 7 天。 */
        private long refreshTokenTtl = 604800;
        private String issuer = "kuma-search";
        /** 请求头名称。 */
        private String header = "Authorization";
        private String tokenPrefix = "Bearer ";
    }

    /** 认证与防爆破配置，对应 {@code kuma.auth.*}。 */
    @Data
    public static class Auth {

        private Login login = new Login();
        /** 邮箱验证码与发信配置，对应 {@code kuma.auth.email.*}。 */
        private Email email = new Email();

        /**
         * 邮箱验证码与发信，对应 {@code kuma.auth.email.*}。
         *
         * <p><b>这里有三层闸，不是一层。</b>理解它们各自防什么是调参的前提：
         * <ol>
         *   <li>{@code sendIntervalSeconds} —— 防「同一个邮箱被反复轰炸」，用户体感问题；</li>
         *   <li>{@code dailyLimitPerEmail} —— 防「一个邮箱被打成筛子」，单点滥用问题；</li>
         *   <li>{@code globalDailyLimit} —— 防「额度被烧穿并开始计费」，成本问题。
         *       <b>这一层才是真正的兜底</b>，前两层都拦不住「换邮箱」的分布式刷量。</li>
         * </ol>
         *
         * <p>额度基准见 {@code docs/07-deployment.md} §十二：阿里云 DirectMail 免费额度为
         * 2000 封总量 / 200 封每日。默认 150 是留了 25% 余量给运维手工发信。
         * <b>购买资源包后必须同步调大这个值</b>，否则付了钱还是发不出去。
         */
        @Data
        public static class Email {

            /**
             * 是否启用邮箱验证码相关接口（发码 / 注册 / 邮箱码登录）。
             *
             * <p>关闭时这三个接口统一返回「服务未开通」，而不是降级成不校验验证码
             * —— 验证码是这三条链路的凭证本身，没有它就无所谓「校验通过」。
             */
            private boolean enabled = false;

            /**
             * 发信方式：{@code aliyun}（DirectMail HTTP RPC）或 {@code smtp}（标准 SMTP）。
             *
             * <p>两者是<b>互斥</b>装配的，不是"配了哪个用哪个"的优先级关系。
             * 这一点很关键：{@code EmailCodeService} 用的是
             * {@code ObjectProvider#getIfAvailable()}，同时存在两个 {@code MailSender}
             * 会直接抛 {@code NoUniqueBeanDefinitionException}，而且是启动时才炸 ——
             * 默认值取 {@code aliyun} 以保持既有部署行为不变。
             *
             * <p>两者各自的配置项不同：{@code aliyun} 用 {@code access-key-id} 等，
             * {@code smtp} 用 {@code spring.mail.*}（Spring Boot 标准配置）。
             * 切过去时记得把没用到的那组的配置清掉，避免留着一份过期的 AK 在配置文件里。
             */
            private String provider = "aliyun";

            /** 验证码有效期（秒）。默认 5 分钟。 */
            private int codeTtlSeconds = 300;

            /** 同一验证码允许校验失败的次数，达上限立即作废验证码（防 6 位数字被爆破）。 */
            private int maxAttempts = 5;

            /** 同一邮箱两次发码的最小间隔（秒）。 */
            private int sendIntervalSeconds = 60;

            /** 同一邮箱每日发码上限。 */
            private int dailyLimitPerEmail = 10;

            /** 全局每日发码上限，见类注释第 3 点。 */
            private int globalDailyLimit = 150;

            /** 阿里云 AccessKey ID。<b>只授 DirectMail 发信权限，不要用主账号 AK</b>，走环境变量注入。 */
            private String accessKeyId;

            /** 阿里云 AccessKey Secret。走环境变量注入，见 {@code docs/07-deployment.md} §5.2。 */
            private String accessKeySecret;

            /** 发信地址，必须落在已在控制台配置过的发信域名下，如 {@code noreply@notice.465ma.com}。 */
            private String accountName;

            /** 发件人显示名。 */
            private String fromAlias = "KumaSearch";

            /** DirectMail 区域。 */
            private String regionId = "cn-hangzhou";

            /** 是否回信到发信地址。默认 false —— 这是一个单向通知邮箱，不接收回复。 */
            private boolean replyToAddress = false;

            /**
             * 邮件主题模板。{@code %s} 占位为场景名（注册 / 登录）。
             *
             * <p>刻意不做多语言：本项目面向中文用户，多语言模板会把「一份配置对应一个场景」
             * 退化成「一个场景对应多份模板」，收益为零而维护成本翻倍。
             */
            private String subjectTemplate = "【KumaSearch】%s验证码";

            /**
             * 是否打印验证码到日志。
             *
             * <p><b>仅用于本地开发</b>（没有真实发信额度时靠日志拿码）。生产环境打开
             * 等于把验证码写进日志文件，任何有日志读取权限的人都能登录任意邮箱账号。
             */
            private boolean logCode = false;
        }

        /**
         * 登录接口的暴力破解防护参数。
         *
         * <p>这里的两个维度（账号 / IP）都要有，理由见 {@code LoginAttemptGuard} 的类注释：
         * 只锁账号挡不住撞库（每个账号只试几次），只限 IP 挡不住换 IP 打同一个账号。
         */
        @Data
        public static class Login {
            /**
             * 总开关。压测或本地调试时关掉可以避免自己的失败请求把账号锁住；
             * <b>生产环境不要关</b> —— 关掉之后 {@code /api/auth/login} 就是一道没有上限的口子。
             */
            private boolean guardEnabled = true;
            /** 同一账号连续失败多少次后锁定。 */
            private int maxFailuresPerAccount = 5;
            /** 账号锁定时长（秒），默认 15 分钟。 */
            private int accountLockSeconds = 900;
            /**
             * 同一来源 IP 在 {@link #ipWindowSeconds} 内失败多少次后拒绝，默认 20 次。
             *
             * <p>计的是失败数而不是请求数：反向代理后面所有客户端共用代理地址，
             * 按请求数计会在正常登录高峰时把桶打满、让所有人登不进来。
             * 即便如此，这个阈值在代理场景下仍是全局共享的，部署时必须让容器还原真实客户端 IP，
             * 见 {@code docs/06-security.md}。
             */
            private int maxFailuresPerIp = 20;
            /** IP 维度的统计窗口（秒）。比账号锁定短得多：它要快速衰减，避免误伤同网段用户。 */
            private int ipWindowSeconds = 60;
        }
    }

    /** 缓存配置。 */
    @Data
    public static class Cache {
        /** 是否启用 L1 本地缓存。多实例部署时 L1 各自独立，失效依赖 Redis 广播，见 TwoLevelCacheService。 */
        private boolean localEnabled = true;
        /** L1 最大条目数。 */
        private long localMaxSize = 10000;
        /** L1 写入后过期时间（秒）。刻意设得比 L2 短，让多实例间的数据不一致窗口有上限。 */
        private long localTtlSeconds = 60;
        /**
         * L2 默认过期时间（秒）。调用方未指定 TTL 时使用。
         *
         * <p>搜索、联想等场景都会显式传自己的 TTL（见 {@link #searchTtlSeconds} /
         * {@link #suggestTtlSeconds}），这个值只作为兜底。
         */
        private long redisTtlSeconds = 300;
        /** 搜索结果缓存过期时间（秒）。设短一些：搜索结果对时效性敏感，且热点集中。 */
        private long searchTtlSeconds = 60;
        /** 搜索建议缓存过期时间（秒）。 */
        private long suggestTtlSeconds = 600;
        /** 缓存 null 值的过期时间（秒），用于防止缓存穿透；必须显著短于正常 TTL。 */
        private long nullTtlSeconds = 30;
        /** 防击穿：重建缓存时的互斥锁等待时间（毫秒）。 */
        private long lockWaitMillis = 500;
    }

    /** 检索行为配置。 */
    @Data
    public static class Search {
        /** 默认每页条数。 */
        private int defaultPageSize = 10;
        /** 模糊匹配时允许的最大编辑距离，超过 2 会带来大量无关结果。 */
        private int fuzzyMaxExpansions = 50;
        /** fuzzy 的 prefix_length：前 N 个字符必须精确匹配，能显著降低模糊查询的开销。 */
        private int fuzzyPrefixLength = 1;
        /** 建议接口默认返回条数。 */
        private int suggestSize = 10;
        /** 热词榜默认展示条数。 */
        private int hotWordSize = 10;
        /** 摘要截取长度（字符）。 */
        private int summaryLength = 120;
        /** 单个 facet 字段默认返回的桶数量。 */
        private int facetSize = 10;
        /** 是否记录搜索日志。高 QPS 下若直写 MySQL 会拖垮数据库，默认只写 Redis，落库走异步。 */
        private boolean searchLogEnabled = true;
        /** 单次查询允许的最大过滤条件数，防止构造超复杂查询打爆集群。 */
        private int maxFilterCount = 20;
        /** 单次查询允许的最大聚合字段数。 */
        private int maxFacetCount = 5;
    }

    /**
     * 索引事件的投递方式。
     *
     * <p>这个开关是「换消息中间件不改业务代码」这条约束的落点：
     * 业务侧只依赖 {@code IndexEventPublisher} / {@code IndexEventConsumer} 接口，
     * 改这里的一行配置就切换了实现，不需要动任何 service 代码。
     *
     * <p>三种取值的适用场景：
     * <ul>
     *   <li>{@code db-outbox} —— 默认。事件写进 MySQL 的 Outbox 表，与业务写入同事务。
     *       轻量、零额外组件，是单机阶段的正解。</li>
     *   <li>{@code memory} —— 仅用于本地开发与单元测试，进程重启即丢事件。</li>
     *   <li>{@code rocketmq} —— 预留。切到这个值需要先把 {@code RocketMqPublisher} 实现完整。</li>
     * </ul>
     */
    @Data
    public static class Mq {
        /** 投递方式：db-outbox / memory / rocketmq。 */
        private String type = "db-outbox";
        /** RocketMQ 的 NameServer 地址，仅在 type=rocketmq 时使用。 */
        private String nameServer;
        /** 索引事件的 topic，仅在 type=rocketmq 时使用。 */
        private String topic = "kuma-index-event";
        /** Outbox 扫描间隔（毫秒），仅在 type=db-outbox 时使用。 */
        private long outboxScanIntervalMillis = 1000;
    }

    /** 数据同步配置。 */
    @Data
    public static class Sync {
        /** 增量同步批次大小。 */
        private int batchSize = 500;
        /** 增量同步任务开关（多实例部署时只应有一个实例开启，或用分布式锁保证互斥）。 */
        private boolean incrementalEnabled = true;
        /** 对账任务开关。 */
        private boolean reconcileEnabled = true;
        /** Outbox 消费失败的最大重试次数，超过后进入死信状态等待人工介入。 */
        private int maxRetry = 5;
        /** 重试退避基数（秒），实际等待时间按指数增长：base * 2^(retryCount-1)。 */
        private int retryBackoffSeconds = 10;
        /** 对账时允许的差异容忍条数，超过则告警。 */
        private int reconcileTolerance = 0;
        /** 增量同步的 overlap 秒数：每次从 lastSyncTime 往前回溯这么久，防止边界时刻的数据被漏掉。 */
        private int overlapSeconds = 5;

        /**
         * 自动建索引开关。
         *
         * <p>默认<b>开</b>，与其它几个任务开关的默认值一致。理由：它只做"补齐缺失的索引"，
         * 不做任何破坏性动作 —— 已经存在的索引一律不碰（判断条件是 {@code !exists}），
         * 因此它不可能覆盖或删掉线上正在用的索引。开着的最坏结果是多建了一个本该建的索引。
         */
        private boolean autoCreateEnabled = true;
    }

    /**
     * 联想（下拉建议）配置，对应 {@code kuma.suggest.*}。
     *
     * <p>只影响「索引建议」这一条来源。个人历史与热词榜分别由用户本次会话与搜索日志驱动，
     * 不受这里控制（见 {@code SuggestServiceImpl} 对三条来源合并顺序的说明）。
     */
    @Data
    public static class Suggest {

        /**
         * 每个类型最多灌入多少条建议词。
         *
         * <p><b>这个上限是本功能的"安全阀"，不是优化项。</b>completion 字段的 FST 常驻堆内存，
         * 采集类内容的条数会持续增长（种子站仍在采集），没有上限的话，
         * 堆占用会随着采集量一路涨上去，而故障形态是"某天开始 ES 节点频繁 GC"，
         * 与"联想"这件事在现象上完全看不出关联。
         *
         * <p>因此灌词时按 {@code bizWeight} 降序取前 N 条：
         * 权重高的内容（越靠近种子入口、越像正式栏目页）优先进入下拉框，
         * 被截掉的是权重最低的那部分 —— 它们本来就排在搜索结果的后几页。
         */
        private int maxDocsPerType = 5000;

        /** 定时全量重建联想索引的开关。关掉后只保留"内容发布时增量灌入"这条路。 */
        private boolean rebuildEnabled = true;

        /**
         * 定时全量重建的间隔（毫秒），默认 6 小时。
         *
         * <p>为什么需要定时全量，而不是只靠增量：增量只覆盖"走本系统接口的写入"，
         * 存量数据（启用本功能之前就已发布的内容）没有任何人会去补 ——
         * 这与主索引需要 {@code IndexBootstrapJob} 灌数据是同一个道理。
         *
         * <p>为什么不设得更短：每次全量都是「删掉联想索引 → 重建 → 重灌」，
         * 重建期间联想是空的（见 {@code SuggestWordServiceImpl#rebuild} 对取舍的说明）；
         * 而新增内容的可见性由增量那条路保证，不需要靠缩短全量周期来达成。
         */
        private long rebuildIntervalMillis = 6 * 60 * 60 * 1000L;
    }

    /**
     * 匿名接口限流，对应 {@code kuma.rate-limit.*}。
     *
     * <p>只覆盖「无需登录即可调用」的接口。需要登录的接口已经有令牌这道门槛，
     * 再叠一层 IP 限流只会让 NAT 后面的用户互相拖累。
     *
     * <p><b>阈值口径按「前端一次操作会发几个请求」来定，不是按「一次搜索」来定。</b>
     * 搜索结果页会并发发两个请求（站内 + 网页采集），阈值若按一次搜索设定，
     * 用户两次搜索就会被拦 —— 这是最容易在联调时被误判成「限流坏了」的地方。
     */
    @Data
    public static class RateLimit {

        /**
         * 总开关。关掉等于对所有匿名接口取消限流。
         *
         * <p>只在本地压测/调试时关；生产关掉后 ES 直接对外裸奔。
         */
        private boolean enabled = true;

        /** 各维度默认的窗口长度（秒）。单个维度可用 {@link Limit#getWindowSeconds()} 覆盖。 */
        private int windowSeconds = 60;

        /** {@code POST /api/search}、{@code POST /api/search/web} 共用的 IP 维度。 */
        private Limit search = new Limit(30);

        /** {@code GET /api/search/suggest}：前端每次击键都会触发，阈值必须显著放宽。 */
        private Limit suggest = new Limit(120);

        /** {@code GET /api/search/hot}。 */
        private Limit hot = new Limit(60);

        /**
         * {@code GET /api/search/types}：搜索页初始化时取一次可检索类型列表。
         *
         * <p>与热词榜同档：它不是用户操作触发的接口，正常使用下每个页面生命周期只调一次，
         * 阈值给宽一些也不会被刷穿，而给窄了会让"多开几个标签页"的正常用户被误伤。
         */
        private Limit types = new Limit(60);

        /**
         * {@code POST /api/auth/email-code} 的 IP 维度。
         *
         * <p>窗口取 1 小时而不是 1 分钟：这个接口的代价是「发一封真金白银的邮件」，
         * 分钟级的窗口意味着攻击者每分钟都能刷满一轮，而邮箱日配额早就被打穿了。
         */
        private Limit emailCode = new Limit(10, 3600);

        /**
         * 前台投稿（{@code /api/content/submit}）的**用户**维度。
         *
         * <p>这里是本配置里<b>唯一按用户计数</b>的维度，原因：投稿是已登录接口，
         * 而"同一个 IP 后面坐着几个人"是完全不可知的 —— 校园网、公司出口、运营商 NAT
         * 都会让大量不相关的人共用一个出口 IP。只按 IP 限流的结果是
         * 「同宿舍的人正常投稿把另一个人顶掉」；只按用户限流则挡不住批量注册小号。
         * 因此两个维度都上，各自独立计数，任一命中即拦。
         *
         * <p>窗口取 1 小时：投稿的正常节奏是"写完一篇发一篇"，
         * 分钟级窗口拦不住刷稿（攻击者可以每个窗口投 10 条），
         * 而小时级窗口下 10 条的额度对真人写手绰绰有余。
         */
        private Limit contentSubmit = Limit.of(60, 10, 3600);

        /**
         * {@code PUT /api/auth/profile/password} 的**用户**维度。
         *
         * <p>ipLimit 配 0（不限流）是有意的：这个接口要防的是"猜到某个账号的原密码"，
         * 而这件事只与账号有关。配 IP 维度挡不住换 IP 的爆破，却会让共用出口的
         * 正常用户互相顶掉额度。真正兜底的是登录侧那套（改密成功后旧密码即失效，
         * 且失败会写日志）。
         *
         * <p>窗口 1 小时 / 5 次：正常人改密码试错不会超过两次，
         * 而每次尝试都要付一次 BCrypt（约 200~300ms），这个额度也顺带限制了算力消耗。
         */
        private Limit profilePassword = Limit.of(0, 5, 3600);

        /**
         * {@code PUT /api/auth/profile/email} 的**用户**维度。
         *
         * <p>与改密码同样的理由（只按账号有意义）。注意它<b>不是</b>发信的闸 ——
         * 发码侧的邮箱节流与日配额在 {@code EmailCodeService} 里，两道管的事不同：
         * 这一道限"换绑动作"，那两道限"发信成本"。
         */
        private Limit profileEmail = Limit.of(0, 5, 3600);

        /**
         * 热词去重窗口（秒）：同一 IP + 同一归一化关键词在该窗口内只计一次热词。
         *
         * <p>只抬高刷榜成本，挡不住分布式刷量，热词榜仍需人工巡检。
         */
        private int hotDedupSeconds = 600;

        /**
         * 热词去重总开关，见 {@link #hotDedupSeconds}。
         *
         * <p>关掉之后热词榜对「同一个 IP 反复搜同一个词」完全不设防，
         * 只在需要观察真实搜索量的压测场景下关闭。
         */
        private boolean hotDedupEnabled = true;

        /** 单维度限流阈值。 */
        @Data
        public static class Limit {
            /** 窗口内允许的请求数。配 0 表示该维度不限流。 */
            private int ipLimit;
            /**
             * 按**用户**维度计数的阈值。配 0 表示该维度不限流。
             *
             * <p>只有「必须登录才能调用」的接口才该用它：匿名接口没有稳定的用户标识，
             * 拿 IP 顶替会与 {@link #ipLimit} 计成同一件事，等于同一个限制写了两遍。
             */
            private int userLimit;
            /** 该维度的窗口（秒）。配 0 表示沿用上层 {@code windowSeconds}。 */
            private int windowSeconds;

            public Limit() {
            }

            public Limit(int ipLimit) {
                this.ipLimit = ipLimit;
            }

            public Limit(int ipLimit, int windowSeconds) {
                this.ipLimit = ipLimit;
                this.windowSeconds = windowSeconds;
            }

            /**
             * 同时带 IP 与用户两个阈值的限流档位。
             *
             * <p>用静态工厂而不是再加一个三参构造器：既有构造器是
             * {@code (ipLimit, windowSeconds)}，新增的会是 {@code (ipLimit, userLimit, windowSeconds)}，
             * 两者在调用处都只是一串裸数字，读不出来第二个数到底是"窗口秒数"还是"用户阈值"。
             * 命名方法把这个歧义消在调用点上。
             */
            public static Limit of(int ipLimit, int userLimit, int windowSeconds) {
                Limit limit = new Limit();
                limit.ipLimit = ipLimit;
                limit.userLimit = userLimit;
                limit.windowSeconds = windowSeconds;
                return limit;
            }

            /** 解析出实际生效的窗口：本维度没配就用兜底值。 */
            public int effectiveWindowSeconds(int fallback) {
                return windowSeconds > 0 ? windowSeconds : fallback;
            }
        }
    }

    /**
     * 人机校验（图形验证码），对应 {@code kuma.captcha.*}。
     *
     * <p><b>默认关闭，且关闭时三个入口（发码 / 注册 / 邮箱码登录）都会拒服务</b>，
     * 而不是「跳过校验直接放行」。理由：一个默认放行的验证码开关，
     * 上线时忘了打开不会产生任何可观察的错误，只会静默地少一道防线。
     * 这里选择「不打开就用不了」，把配置遗漏变成必须当场解决的问题。
     *
     * <h3>⚠️ 这里<b>没有</b>取图 / 校验的路径配置</h3>
     * 曾经有过 {@code get-url} / {@code check-url} 两个字段，那是基于
     * 「AJ-Captcha 的 starter 自带控制器、默认挂在 {@code /captcha/get}」这个假设写的。
     * 实测 2.0.0 之后这个假设不成立：<b>核心包不含任何控制器</b>（也不含任何
     * javax/jakarta.servlet 引用），取图与校验两个端点由本项目的
     * {@code CaptchaController} 提供，路径是 {@code /api/captcha/get} 与
     * {@code /api/captcha/check} —— 写死在 {@code @RequestMapping} 上，不是配置项。
     * 保留两个无人读取的配置字段只会让人以为改它能改路径，因此删掉。
     */
    @Data
    public static class Captcha {

        /** 是否启用滑块校验。 */
        private boolean enabled = false;

        /**
         * 验证码类型。交给 AJ-Captcha 的 {@code captcha.type} 选择实现。
         *
         * <p>只支持 {@code blockPuzzle}（滑块拼图）。上游还提供 {@code clickWord}
         * （文字点选）、{@code curveSlider} 等，但它们的<b>前端交互形态完全不同</b>，
         * 换类型等于重写前端的 {@code SliderCaptcha}，不是改一个配置值就能切的事，
         * 因此这里不把它描述成"可切换"。
         */
        private String type = "blockPuzzle";

        /**
         * 验证码数据的缓存位置：{@code redis} 或 {@code local}。
         *
         * <p><b>多实例部署必须是 {@code redis}</b>。{@code local} 是进程内 Map，
         * 取图请求打到 A 节点、校验请求打到 B 节点时，B 找不到那份挑战数据，
         * 用户看到的是「验证码已失效，请重新获取」—— 一个只在负载均衡把请求分到
         * 不同节点时才出现的、间歇性的失败。用户会以为是网络问题。
         */
        private String cacheType = "redis";

        /**
         * 滑动允许的横向误差（像素）。
         *
         * <p>纵坐标是精确比对（滑块只能水平拖），横向给一点余量是为了让
         * 正常用户不必像素级对齐。这个值直接等于"猜中的难度"：
         * 上限由背景图宽度决定，10px 在约 300px 的底图上约合 3%，
         * <b>不要为了"用户体验"把它调到几十像素</b> —— 那等于把答案范围放大到一眼可猜。
         */
        private int slipOffset = 10;

        /**
         * 底图上的水印文字。
         *
         * <p>它是对截图/转发类攻击的一点标记，不构成任何安全能力。
         * 换成自己站点名即可。
         */
        private String waterMark = "KumaSearch";

        /**
         * 是否对前端提交的坐标做 AES 加密。
         *
         * <h3>⚠️ 本项目只支持 {@code false}，配成 true 会拒绝启动</h3>
         * 不是"不推荐"，是<b>不支持</b> —— 拦在启动期的理由见下。
         *
         * <p>上游打开它时会随取图响应下发 {@code secretKey}，并要求<b>浏览器端</b>
         * 用它把坐标加密后提交（服务端用同一个 key 解密，算法是 AES-ECB/PKCS7）。
         * 而 Web Crypto 刻意不提供 ECB，前端要满足它就得手写一份 AES ——
         * 那段代码一旦在 padding 或密钥编码上有细微偏差，症状是
         * 「所有人都过不了滑块」，且看起来像后端故障。开关本身也换不来安全性：
         * 密钥必须下发给前端才能用，因此对任何持有浏览器的人都可见，
         * 真正的判定始终是服务端比对坐标。
         *
         * <p>真正让它必须被拦下的原因是第三个：即便前端配合，本项目的后端也
         * <b>算不出那枚一次性凭证</b>。上游把 {@code token + "---" + 坐标}
         * 用密钥加密后写成缓存键，aes-status=false 时密钥为空、加密退化为原文拼接，
         * 服务端因此能自己拼出这枚串（见 {@code AjCaptchaVerifier#buildVerification}）；
         * 打开加密之后，密钥只在被消费掉的缓存项里，服务端再也无法重算它。
         * 也就是说配成 true 的结果是"每一条注册/登录链路都失败"，
         * 而不是"稍微更安全一点"。
         */
        private boolean aesStatus = false;

        /**
         * 干扰图数量（0/1/2）。在底图上额外抠出几个形状，增加"数缺口"的难度。
         *
         * <p>默认 0：干扰图会让正常用户也更容易对错位置，而它挡不住任何自动化手段
         * （形状位置同样在返回的底图里）。
         */
        private int interferenceOptions = 0;

        /** {@code local} 缓存的最大条目数。只对 {@code cacheType=local} 有意义。 */
        private int cacheNumber = 1000;

        /** {@code local} 缓存的过期清理间隔（秒）。同上。 */
        private int timingClearSeconds = 180;

        /**
         * 是否启用 AJ-Captcha 自带的接口频次限制。
         *
         * <p>默认 {@code false}：本项目已经有 {@code RateLimitGuard} 按 IP 维度限流，
         * 而上游那套是按"客户端标识 / 浏览器指纹"计的，两套并存会出现
         * 「同一时刻一个说超限、另一个说没超」的矛盾现象，排查时非常费解。
         * 限流统一由 {@code RateLimitGuard} 负责，这里保持关闭。
         */
        private boolean reqFrequencyLimitEnable = false;

        /**
         * {@code captchaVerification} 凭证的一次性消费 TTL（秒）。
         *
         * <p>滑块校验通过后返回的凭证只允许被业务接口用掉一次；
         * 这个 TTL 是「凭证已登记但业务接口没来消费」时的清理兜底，防的是凭证重放。
         */
        private int verificationTtlSeconds = 120;
    }

    /**
     * 网页采集，对应 {@code kuma.crawl.*}。
     *
     * <p><b>默认关闭。</b>采集会对外发起真实请求，误开（尤其是误配种子站）的后果是对第三方站点造成压力，
     * 属于「悄悄跑起来会造成麻烦」的功能，因此要求显式开启。
     */
    @Data
    public static class Crawl {

        /** 采集总开关。关闭时 {@code CrawlScheduler} 不排任何任务。 */
        private boolean enabled = false;

        /** 调度间隔（毫秒）。 */
        private long scanIntervalMillis = 30000;

        /** 单轮从队列取多少个 URL。 */
        private int batchSize = 20;

        /** 单轮最多并发抓取多少个 URL。按域名限速是另一层约束，两者都要满足。 */
        private int concurrency = 4;

        /** 连接超时（毫秒）。 */
        private int connectTimeoutMillis = 5000;

        /** 读超时（毫秒）。抓的是别人的站点，超时要给足，但不能不设。 */
        private int readTimeoutMillis = 10000;

        /** 单页最大读取字节数。超过直接截断 —— 防止一个坏 URL 把内存吃光。 */
        private int maxContentBytes = 2 * 1024 * 1024;

        /** User-Agent。**必须能标识本站并提供联系方式**，这是「礼貌爬取」的一部分，也便于对方在有异议时找到人。 */
        private String userAgent = "KumaSearchBot/1.0 (+https://465ma.com/bot)";

        /** 失败重试上限，超过后进死信（status=4）等人工处理，不再自动重试。 */
        private int maxRetry = 3;

        /** 重试退避基数（秒）：第 n 次重试等待 {@code base * 2^(n-1)}。 */
        private int retryBackoffSeconds = 60;

        /** 未在种子站上单独配置时的默认 QPS。0.5 = 每 2 秒一个请求。 */
        private double defaultQps = 0.5;

        /**
         * 抓取租约时长（秒）。超过这个时间仍是「抓取中」（status=1）的记录会被回收为待抓。
         *
         * <p>只在进程崩溃/被杀时才会触发。这是单机版的兜底；多节点版本改用
         * {@code DistributedLock} 做真正的互斥，见 {@code docs/08-roadmap.md}。
         */
        private int leaseTimeoutSeconds = 300;

        /** robots.txt 缓存时长（秒）。同一个域反复拉 robots 既是浪费也不礼貌。 */
        private int robotsCacheSeconds = 3600;

        /** 敏感词库是否需要定期重载（秒）。词库改完不必重启进程。 */
        private int sensitiveReloadSeconds = 300;

        /**
         * 采集到的内容是否直接进入已发布状态（{@code status=1}），从而立刻可被搜到。
         *
         * <p>默认<b>开</b>。这与"审核后才发布"相比少了一道人工闸，取舍如下：
         * <ul>
         *   <li>采集是<b>持续不断</b>的（种子站在跑，每几分钟就有新页面进来），
         *       而人工审核是间歇的。默认走人工审核的结果是"审核队列永远积压"，
         *       真正的表现是索引里绝大多数内容停在待审状态、搜索几乎搜不到东西 ——
         *       一条内容都没被放行过，比偶尔放进来一条不该放的更常见。</li>
         *   <li>本地敏感词库<b>仍然在发布前过滤</b>：命中的内容直接置为驳回（{@code status=2}）
         *       并留痕进审核队列，不会因为开了这个开关就流出去。</li>
         *   <li>人工审核因此从"逐条放行"变成"高风险复核 + 抽检"，
         *       配套的批量放行/驳回接口见 {@code CrawlAuditController}。</li>
         * </ul>
         *
         * <p>关掉它即回到"全部待审"的老行为，不需要改代码 ——
         * 但要注意已发布的内容不会因为关掉开关而退回待审，那是人工下架的事。
         */
        private boolean autoPublish = true;

        /**
         * 正文抽取实现：{@code jsoup}（默认）或 {@code regex}。
         *
         * <p>默认换成 {@code jsoup} 的理由是它修掉的正是正则实现最容易出错的地方：
         * 正则剥标签在原理上就不可能正确处理嵌套结构，而 {@code <script>} 里含有
         * {@code "</script>"} 字面量、属性值里含 {@code ">"} 这两类写法会让
         * 正则版把 JS 混进正文（送去做敏感词匹配与指纹计算）。
         *
         * <p>{@code regex} 保留为可选值有实际用途：它零依赖、行为完全可预期，
         * 在排查"某个页面抽取结果不对"时是很好的对照组 ——
         * 两个实现的输出差异本身就能指出问题出在解析还是在页面结构。
         */
        private String extractor = "jsoup";

        /**
         * 是否允许抓取内网/环回地址。
         *
         * <p><b>生产环境必须保持 false。</b>种子站是可配置的，而配置项一旦能指向
         * {@code 127.0.0.1} 或 {@code 169.254.169.254}，这个采集器就变成了打内网的 SSRF 工具
         * —— 云厂商元数据服务（169.254.169.254）就在这个范围里。只在写单测时打开。
         */
        private boolean allowPrivateHosts = false;
    }
}
