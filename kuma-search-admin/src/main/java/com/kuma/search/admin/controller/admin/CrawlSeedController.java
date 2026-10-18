package com.kuma.search.admin.controller.admin;

import com.kuma.search.admin.service.CrawlSeedService;
import com.kuma.search.common.result.R;
import com.kuma.search.crawl.CrawlScheduler;
import com.kuma.search.repository.entity.CrawlSeed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 采集种子站与队列的运维接口。
 *
 * <p>权限码用 {@code admin:sync:manage}（与 {@code SyncTaskController} 相同）而不是
 * 新造一个 {@code admin:crawl:manage}：两者都是「让某个后台任务去某处取数据」，
 * 有权限配同步任务的人本来就该有权限配采集。新造权限码会带来
 * {@code sql/data.sql}、各 Controller 注解、权限矩阵文档三处同步修改的成本，
 * 而它换来的区分在现实中不存在（这两个权限总是同时授予同一个人）。
 *
 * <h3>⚠️ 这里的每个写操作都在放宽一个对外发起真实请求的能力</h3>
 * 采集器会主动请求第三方站点。因此：
 * <ul>
 *   <li>新建种子站默认是<b>停用</b>状态（见 {@code CrawlSeedService#create}），
 *       需要显式再调一次启用 —— 让"加一个站"和"开始采这个站"成为两个动作；</li>
 *   <li>地址校验（SSRF 防护）在服务层，见 {@link CrawlSeedService} 的类注释；</li>
 *   <li>总开关 {@code kuma.crawl.enabled} 默认 false，关着的时候这里的配置
 *       一条也不会被执行 —— 后台能看到站，但不会有请求发出去。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/crawl/seed")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:sync:manage')")
@Tag(name = "采集种子站", description = """
        采集种子站与抓取队列的运维。

        **本组全部端点需要权限码 `admin:sync:manage`**（与同步任务共用同一个权限码，未新造权限码）。

        这里的每个写操作都在放宽「让采集器对外发起真实请求」的能力：新建的种子站默认是停用状态，需显式再启用；
        地址校验（SSRF 防护）在服务层；总开关 `kuma.crawl.enabled` 默认关闭时，这里的配置一条也不会被执行。
        """)
@SecurityRequirement(name = "bearerAuth")
public class CrawlSeedController {

    private final CrawlSeedService crawlSeedService;

    /**
     * 手工触发扫描用。注入调度器而不是让控制器自己拼一遍"取任务 → 抓取"——
     * 那会把抓取线程池、按域名分组、限速这些约束在控制器里复刻一遍，
     * 而手工触发与定时触发必须<b>完全同一条路径</b>，否则"手工能跑、定时不能跑"这类
     * 只在一边出现的差异会永远查不出来。
     */
    private final CrawlScheduler crawlScheduler;

    /** 列出全部种子站（含停用）。 */
    @GetMapping
    @Operation(summary = "列出种子站", description = """
            返回全部采集种子站（含已停用），按更新时间倒序。""")
    public R<List<CrawlSeed>> list() {
        return R.ok(crawlSeedService.list());
    }

    /**
     * 队列概览：每个种子站的待抓量与已抓量、配额是否用尽。
     *
     * <p>与 {@link #list} 分开一个接口，是因为它要对每个种子站各跑两次 COUNT ——
     * 打开种子站列表页时不一定需要这组数字，让列表接口替它承担这份开销不划算。
     */
    @GetMapping("/queue")
    @Operation(summary = "查看队列概览", description = """
            按种子站逐个统计待抓量与已抓量、页数上限，以及配额是否用尽。
            对每个种子站各跑两次 COUNT，比列表接口重，列表页不需要这组数字时不要调用。
            配额是否用尽由服务端算好返回，前端不要自行推导，以免出现「后台显示还能采、调度器已停」的两边不一致。""")
    public R<List<Map<String, Object>>> queueOverview() {
        return R.ok(crawlSeedService.queueOverview());
    }

    /** 新建种子站。默认停用，见类注释。 */
    @PostMapping
    @Operation(summary = "新建种子站", description = """
            落库一条采集种子站并返回主键 id。
            坑：新建的种子站默认是「停用」状态，需要显式再调一次状态接口启用后才会开始采集（让「加一个站」与「开始采这个站」是两个动作）。
            entryUrl 只允许 http/https，且只允许公网地址，指向本机或内网会被 SSRF 防护拒绝。""")
    public R<Long> create(@RequestBody CrawlSeed seed) {
        return R.ok(crawlSeedService.create(seed));
    }

    /** 更新种子站。坑：改 entryUrl / domain 不会清空已有队列，见服务层注释。 */
    @PutMapping
    @Operation(summary = "更新种子站", description = """
            按 id 更新，body 必须带 id，且必须提交完整配置（会跑字段校验，漏传 entryUrl 会被判为参数错误）。
            坑：修改 entryUrl 或 domain 不会清空已有队列，旧域名已排队的 URL 仍会按原计划抓完；要停采请改用状态接口把 status 置 0。""")
    public R<Void> update(@RequestBody CrawlSeed seed) {
        crawlSeedService.update(seed);
        return R.ok();
    }

    /**
     * 启用 / 停用种子站。
     *
     * <p>单独一个接口而不是复用 {@link #update}：启停是运维最常用的动作，
     * 而 {@code update} 要求提交整行（漏传的字段会被置空）。
     * 让停采这个动作只需要一个布尔值，可以显著降低误操作面。
     */
    @PostMapping("/{id}/status")
    @Operation(summary = "启停用种子站", description = """
            只修改指定种子站的启用状态，请求体只需一个 status 字段（1 启用 / 0 停用）。
            单独一个端点而不是复用更新接口：update 要求提交整行、漏传的字段会被置空，而启停是运维最高频的动作，不该携带这种误操作面；
            本端点只写 status 一个字段，并发修改其它字段不受影响。""")
    public R<Void> changeStatus(
            @Parameter(description = "种子站主键 id", example = "1") @PathVariable Long id,
            @RequestBody CrawlSeed body) {
        crawlSeedService.changeStatus(id, body.getStatus());
        return R.ok();
    }

    /**
     * 删除种子站。
     *
     * <p>坑：这不只是删一条配置 —— 服务层会把该站队列里<b>尚未抓取</b>的任务一并作废，
     * 否则它们会在种子站消失后继续被抓取入库。前端必须弹二次确认并说清这一点，
     * 而不是笼统地问"确定要删除吗"。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除种子站", description = """
            删除种子站，并把该站队列中尚未抓取（待抓 / 抓取中 / 待重试）的 URL 一律置为「已跳过」，两步在同一事务内完成。
            坑：这不只是删一条配置 —— 队列任务会一并作废；前端必须做二次确认并说清这一点。已抓完的行不动。""")
    public R<Void> delete(
            @Parameter(description = "种子站主键 id", example = "1") @PathVariable Long id) {
        crawlSeedService.delete(id);
        return R.ok();
    }

    /**
     * 立即执行一轮采集扫描。
     *
     * <p>存在的理由只有一个：采集的调度周期是 30 秒，而"我刚改完配置，现在到底生效了没有"
     * 是采集运维里最高频的问题。没有这个入口，每一次验证都要干等 30 秒，
     * 而且看到的现象仍是异步的、分不清是"没生效"还是"还没轮到"。
     *
     * <p><b>它是同步执行的</b>：接口会阻塞到本轮结束（抓取的网络耗时都在这段时间里）。
     * 这与后台"点一下、马上看到数字"的期望一致，但前端不该按普通请求设超时 ——
     * 一轮的耗时受目标站响应速度影响，可达数秒到数十秒。
     *
     * <p>返回的四个数字怎么读、分别对应哪种故障，见 {@code CrawlScheduler.ScanSummary} 的注释；
     * 这里只强调最容易误判的一种：<b>{@code seedEnqueued = 0} 且 {@code claimed = 0}</b>
     * 表示没有任何"启用中"的种子站（新建的种子站默认是停用的）。
     */
    @PostMapping("/scan")
    @Operation(summary = "立即扫描一次", description = """
            手工触发一轮采集扫描，同步执行到本轮结束才返回，无需等待 30 秒的调度周期。

            返回四个数字，用于区分「什么都没发生」的几种不同原因：
            - `claimed`：本轮认领的待抓 URL 条数。为 0 说明队列里没有待抓任务；
            - `seedEnqueued`：本轮由种子站入口 URL 新入队的条数。为 0 说明没有启用中的种子站（新建的种子站默认停用，需先启用）；
            - `recovered`：回收的过期租约条数。大于 0 是正常的自愈现象，不是故障；
            - `domains`：本轮实际处理的域名个数。

            校验顺序：先确认采集总开关是打开的，没打开会直接报错而不是返回 0 —— 后者会被误读成「扫了但没抓到」。
            坑：一轮的耗时取决于目标站响应速度（可达数十秒），前端不要按普通请求设超时。""")
    public R<CrawlScheduler.ScanSummary> scanNow() {
        return R.ok(crawlScheduler.scanOnceManually());
    }
}
