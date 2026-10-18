package com.kuma.search.admin.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档（springdoc + Swagger UI）。
 *
 * <p>入口：{@code http://localhost:18082/swagger-ui.html}。
 * 端点：{@code /v3/api-docs}（JSON）、{@code /v3/api-docs/{group}}（分组 JSON）。
 *
 * <h3>整个文档由 {@code kuma.swagger.enabled} 一个开关控制，默认关闭</h3>
 * 开关同时作用在两处，缺一不可：
 * <ul>
 *   <li>{@code springdoc.api-docs.enabled} / {@code springdoc.swagger-ui.enabled}
 *       ——真正决定端点存不存在（见 application.yml）。关掉时 springdoc 不注册 handler，
 *       请求落到 {@code NoResourceFoundException}，被项目的 {@code GlobalExceptionHandler}
 *       包成 <b>HTTP 200 + {@code {"code":10001}}</b>（已实测；不是 404，也不是 403 ——
 *       本项目所有业务失败都用 HTTP 200 承载）。判断开关开没开，看响应体里有没有
 *       {@code openapi} 字段，别看状态码。</li>
 *   <li>本类的 {@link ConditionalOnProperty} ——不注册分组的 Bean。</li>
 * </ul>
 * <p><b>为什么默认关。</b>文档页会把 60 多个端点连同请求体字段结构和错误码一次性摊开，
 * 包括 12 个管理端控制器。它是给开发和运维看的，没有任何理由让公网用户也能拿到这份
 * 「管理后台接口清单 + 参数结构」。要开的时候用环境变量显式开：
 * <pre>
 *   SWAGGER_ENABLED=true java -jar kuma-search-web.jar   # 本地开发
 * </pre>
 * <p>生产环境若确需临时开启，先看 {@code docs/07-deployment.md} —— 那里记了怎么开、
 * 以及开完要记得关。
 *
 * <h3>为什么分成 5 组，而不是一个大列表</h3>
 * 全量 63 个端点堆在一页里，最常做的一件事（"我要看一下检索接口的请求体长什么样"）
 * 要在一屏几百行里翻。分组按<b>使用者的身份</b>切，而不是按 Java 包切：
 * 前两组是任何开发者/前端都能直接调的匿名接口，后三组是管理端，
 * 需要先在页面右上角的 Authorize 里贴 JWT。
 *
 * <p><b>改分组时的坑</b>：组名会直接进 URL（{@code /v3/api-docs/01-检索（匿名可用）}），
 * 这里用了中文是为了下拉框里一眼能认出来。若某个浏览器/代理对非 ASCII 的路径处理有问题，
 * 把 {@code group(...)} 换成纯 ASCII（如 {@code 01-search}）即可，
 * 只影响下拉框显示，不影响任何接口。
 *
 * <h3>认证怎么试</h3>
 * 管理端接口不能直接点。正确顺序：
 * <ol>
 *   <li>在「02-认证与验证码」组里调 {@code POST /api/auth/login}（默认账号 admin / admin123）；</li>
 *   <li>从响应里复制 {@code data.accessToken}；</li>
 *   <li>点页面右上角 <b>Authorize</b>，粘贴<b>裸 token</b>（<b>不要</b>自己加
 *       {@code Bearer } 前缀 —— 前缀由 {@link #openAPI()} 里的 securityScheme 补上，
 *       手工加了会变成 {@code Bearer Bearer eyJ...}，过滤器解析失败，表现为"登录成功但所有管理接口都 401"）；</li>
 *   <li>之后同页面内所有带锁图标的接口都会自动带上 {@code Authorization} 头。</li>
 * </ol>
 *
 * <p><b>注意</b>：token 默认有效期 {@code kuma.jwt.access-token-ttl} 秒（默认 7200，即 2 小时，见 application.yml），
 * 到期后文档页里所有管理接口会集体变 401，此时重新登录换一个 token 即可 ——
 * 那不是接口坏了。
 */
@Configuration
@ConditionalOnProperty(prefix = "kuma.swagger", name = "enabled", havingValue = "true")
public class SwaggerConfig {

    /** bearerAuth 的 scheme 名。{@code @SecurityRequirement(name = "bearerAuth")} 里的字面量必须与它一致。 */
    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    /**
     * 文档的全局信息与认证方式。
     *
     * <p>{@code type = HTTP + bearerFormat = JWT} 是让右上角出现 Authorize 按钮、
     * 并且让粘贴进去的 token 被拼成 {@code Authorization: Bearer <token>} 的原因。
     * 换成 {@code APIKEY + header} 也能用，但那样就不会自动补 {@code Bearer } 前缀，
     * 于是每个人都要在粘贴时自己加前缀、忘了就得到 401。
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("KumaSearch 接口文档")
                        .version("1.0.0")
                        .description("""
                                轻量级通用多类型搜索引擎。

                                **响应体统一结构**：除 Swagger UI 自身的静态资源外，所有接口都返回
                                `{ code, message, data, timestamp, success }`。**`code = 0` 表示成功**
                                （不是 10000，也不是任何一个像"成功"的整数 —— 见 `ResultCode.SUCCESS`），
                                其余取值见 docs/04-api-design.md 的错误码表。**HTTP 状态码与业务 code
                                是两套东西**：业务失败可能仍是 HTTP 200，前端判成败必须看 `code`
                                （或等价的 `success` 布尔），不要看 HTTP 状态。

                                **鉴权**：默认所有接口都需要登录，只有明确标注为匿名的检索与
                                认证接口例外。管理端接口除登录外还要求对应权限码。
                                点击右上角 **Authorize** 粘贴 `POST /api/auth/login` 返回的
                                `data.accessToken`（裸 token，不要加 `Bearer ` 前缀）。

                                **对外措辞说明**：`/api/search/web` 与采集相关接口在本文档中统称
                                「网页采集」，而不是「全网搜索」—— 它检索的是本站按种子站白名单
                                采回来的语料，不是全网。措辞差异是有意保留的，理由见
                                docs/01-requirements.md 的边界说明。"""))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        登录接口返回的 accessToken。填裸 token，
                                        不要自己加 `Bearer ` 前缀（本 scheme 会自动补）。""")))
                // 刻意【不】在这里写全局 security 要求。写了的话匿名接口（检索、登录、
                // 验证码）也会被标上锁图标，看文档的人会以为匿名搜索需要登录 ——
                // 而"搜索不需要登录"正是这套接口设计的核心。
                // 因此改为由每个管理端 Controller 自己用 @SecurityRequirement 声明。
                ;
    }

    /**
     * 站内检索：匿名可用，是新用户最常看的一组，放第一。
     *
     * <p>注意 {@code GET/DELETE /api/search/history} 也在这个组里，但它<b>需要登录</b>
     * （个人数据）。它带锁图标，以注解为准。
     */
    @Bean
    public GroupedOpenApi searchApi() {
        return GroupedOpenApi.builder()
                .group("01-检索（匿名可用）")
                .pathsToMatch("/api/search/**")
                .build();
    }

    /**
     * 注册、登录与验证码。
     *
     * <p>组名里没有写"匿名可用"是刻意的：这一类里**大部分**接口是匿名的
     * （登录、刷新、注册、发码、邮箱码登录、取图、校验滑块），
     * 但 {@code GET /api/auth/me}、{@code POST /api/auth/logout}、
     * {@code POST /api/auth/history/import} 三个**需要登录** ——
     * 它们在 {@code SecurityConfig} 的放行清单之外，落到底部的 {@code authenticated()}。
     * 以文档页上的锁图标为准。
     *
     * <p>匿名的几个内部有滑块、邮箱验证码、防爆破三道闸，用文档页连着调几次可能触发限流
     * （返回 10003 / HTTP 429），那是正常行为。
     */
    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("02-认证与验证码")
                .pathsToMatch("/api/auth/**", "/api/captcha/**")
                .build();
    }

    /**
     * 索引运维与数据同步。
     *
     * <p><b>这一组里有两个接口的顺序不能反</b>：
     * {@code POST /api/admin/index-type/{typeCode}/create-index}（新建）在前，
     * {@code .../rebuild}（重建）在后。重建会自己推导「旧索引 = 当前版本号的物理索引」，
     * 索引从未创建过时它推出来的旧索引不存在，会直接失败。
     * 完整说明见 docs/07-deployment.md §四 步骤 7。
     */
    @Bean
    public GroupedOpenApi indexOpsApi() {
        return GroupedOpenApi.builder()
                .group("03-管理端：索引与同步")
                .pathsToMatch("/api/admin/index-type/**",
                        "/api/admin/sync-task/**",
                        "/api/admin/crawl/seed/**")
                .build();
    }

    /**
     * 内容与审核。
     *
     * <p>{@code POST /api/admin/content} 是唯一的人工录入入口，
     * 写入后由 Outbox 异步推进 ES —— <b>不是同步写 ES</b>，
     * 所以调完接口立刻搜可能搜不到，等 1～2 秒（OutboxDispatchJob 间隔 1s）。
     * 排查见 docs/09-maintenance.md。
     */
    @Bean
    public GroupedOpenApi contentApi() {
        return GroupedOpenApi.builder()
                .group("04-管理端：内容与审核")
                .pathsToMatch("/api/admin/content/**",
                        "/api/admin/crawl/audit/**",
                        "/api/admin/dict/**")
                .build();
    }

    /**
     * 用户与权限。改这一组里的任何东西都直接影响谁能调上面的接口，
     * 误操作（比如把自己的 admin:index:manage 摘掉）会立刻把自己锁在门外。
     */
    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder()
                .group("05-管理端：用户与权限")
                .pathsToMatch("/api/admin/user/**", "/api/admin/role/**")
                .build();
    }
}
