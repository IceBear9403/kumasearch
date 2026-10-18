package com.kuma.search.crawl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.repository.entity.CrawlSeed;
import com.kuma.search.repository.entity.CrawlUrl;
import com.kuma.search.repository.mapper.CrawlUrlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 MySQL 的待抓队列实现。
 *
 * <p>用数据库当队列的理由与代价，见 {@link CrawlFrontier} 的类注释。
 * 这里只说实现上的两个关键选择。
 *
 * <h3>为什么「去重」交给唯一键而不是先查后插</h3>
 * 先 {@code SELECT} 判断存在、再 {@code INSERT} 是经典的竞态写法：
 * 两个线程同时判断「不存在」，随后一个插入成功、一个抛
 * {@link DuplicateKeyException}。而采集的入队是<b>高度并发</b>的
 * （一页解析出几百个链接要同时入队），这个竞态会频繁触发。
 * 因此这里直接插，靠 {@code uk_url_hash} 兜底，捕获重复键异常当作「已存在」处理。
 * 代价是一次无用的 INSERT 尝试，收益是这一整类竞态彻底消失。
 *
 * <h3>为什么 markTerminal 要校验状态</h3>
 * 见 {@link CrawlFrontier#markTerminal} 的说明。这里在代码层再做一次，
 * 是因为把「待抓」误置成终态会让 URL 静默消失 —— 没有异常、没有日志，
 * 只表现为「这个站少采了一部分」。这种问题几乎不可能靠排查发现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbCrawlFrontier implements CrawlFrontier {

    private final CrawlUrlMapper crawlUrlMapper;
    private final UrlNormalizer urlNormalizer;
    private final KumaSearchProperties properties;

    @Override
    public List<CrawlUrl> claim(int batchSize) {
        if (batchSize <= 0) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<CrawlUrl> candidates = crawlUrlMapper.selectFetchable(now, batchSize);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> ids = new ArrayList<>(candidates.size());
        for (CrawlUrl candidate : candidates) {
            ids.add(candidate.getId());
        }

        // 真正抢到的是「影响行数」对应的那批。claimBatch 的 WHERE 里带着 status=0，
        // 被别的线程抢先处理的那些不会命中，因此不会重复抓取。
        int claimed = crawlUrlMapper.claimBatch(ids, now);
        if (claimed != candidates.size()) {
            // 不是错误，只说明有并发。回查一次拿到确定属于本线程的那批，
            // 避免把别人正在处理的 URL 也当成自己的。
            log.debug("认领时发生并发竞争: 期望 {} 实际 {}", candidates.size(), claimed);
            return relistClaimed(ids, now);
        }

        for (CrawlUrl url : candidates) {
            url.setStatus(CrawlUrl.STATUS_FETCHING);
            url.setLockedAt(now);
        }
        return candidates;
    }

    /**
     * 认领发生竞争后，重新查出真正归属本批次的记录。
     *
     * <p>判定条件是「状态为抓取中且 lockedAt 等于本次的 now」——
     * {@code now} 是本方法内的时间戳，只有真正由本次 {@code claimBatch} 改成该值的行
     * 才会匹配。用时间戳当归属标记而不是引入一列 owner，
     * 是因为 owner 列需要额外的列变更与清理逻辑，而这里的时间戳本就写入了同一批行。
     */
    private List<CrawlUrl> relistClaimed(List<Long> ids, LocalDateTime now) {
        LambdaQueryWrapper<CrawlUrl> wrapper = new LambdaQueryWrapper<CrawlUrl>()
                .in(CrawlUrl::getId, ids)
                .eq(CrawlUrl::getStatus, CrawlUrl.STATUS_FETCHING)
                .eq(CrawlUrl::getLockedAt, now);
        List<CrawlUrl> claimed = crawlUrlMapper.selectList(wrapper);
        return claimed == null ? Collections.emptyList() : claimed;
    }

    @Override
    public boolean enqueue(CrawlSeed seed, String normalizedUrl, String parentUrlHash, int depth) {
        if (normalizedUrl == null) {
            return false;
        }
        String urlHash = urlNormalizer.hash(normalizedUrl);
        CrawlUrl record = CrawlUrl.builder()
                .urlHash(urlHash)
                .url(normalizedUrl)
                .domain(urlNormalizer.hostOf(normalizedUrl))
                .seedId(seed == null ? null : seed.getId())
                .parentUrlHash(parentUrlHash)
                .depth(depth)
                .status(CrawlUrl.STATUS_PENDING)
                .retryCount(0)
                .nextFetchTime(LocalDateTime.now())
                .build();
        try {
            crawlUrlMapper.insert(record);
            return true;
        } catch (DuplicateKeyException e) {
            // 已存在。**不更新已有记录** —— 见 CrawlFrontier#enqueue 的说明：
            // 重置状态会让 robots 禁止、死信、跳过的 URL 反复复活。
            return false;
        }
    }

    @Override
    public void markDone(CrawlUrl url, String contentHash, int httpStatus, String etag, String lastModified) {
        url.setStatus(CrawlUrl.STATUS_DONE);
        url.setContentHash(contentHash);
        url.setHttpStatus(httpStatus);
        url.setEtag(etag);
        url.setLastModified(lastModified);
        url.setLockedAt(null);
        url.setErrorMsg(null);
        // 已抓取的页面不再需要重抓时间；清空它可以让「重抓」这件事改由
        // 内容更新策略显式驱动，而不是靠一个遗留的时间戳意外命中。
        url.setNextFetchTime(null);
        crawlUrlMapper.updateById(url);
    }

    @Override
    public void markRetry(CrawlUrl url, int httpStatus, String errorMessage, Long retryAfterSeconds) {
        int retryCount = url.getRetryCount() == null ? 0 : url.getRetryCount();
        retryCount++;
        // 截断到列宽（1000）。超长错误信息直接写库会被 MySQL 拒绝或静默截断，
        // 显式截断至少能在日志里看到完整的原文。
        url.setStatus(CrawlUrl.STATUS_RETRY);
        url.setRetryCount(retryCount);
        url.setHttpStatus(httpStatus);
        url.setErrorMsg(truncate(errorMessage, 1000));
        url.setLockedAt(null);
        url.setNextFetchTime(LocalDateTime.now().plusSeconds(backoffSeconds(retryCount, retryAfterSeconds)));
        crawlUrlMapper.updateById(url);
    }

    @Override
    public void markTerminal(CrawlUrl url, int status, String reason) {
        if (!isTerminalStatus(status)) {
            // 拒绝而不是静默接受：把「待抓」误置成「已抓」类状态会让 URL 静默消失，
            // 而这类问题不会产生任何异常，只能靠这里主动拦下来
            throw new IllegalArgumentException("不允许置为终态以外的状态: " + status);
        }
        url.setStatus(status);
        url.setErrorMsg(truncate(reason, 1000));
        url.setLockedAt(null);
        url.setNextFetchTime(null);
        crawlUrlMapper.updateById(url);
    }

    @Override
    public void defer(CrawlUrl url, long waitMillis) {
        url.setStatus(CrawlUrl.STATUS_PENDING);
        url.setLockedAt(null);
        // 至少推迟 1 秒：waitMillis 为 0 时把状态退回待抓会让本轮立刻又取到同一条，
        // 形成一个空转的忙循环，把 CPU 和数据库连接都吃满。
        url.setNextFetchTime(LocalDateTime.now().plus(Duration.ofMillis(Math.max(waitMillis, 1000L))));
        crawlUrlMapper.updateById(url);
    }

    @Override
    public int recoverExpiredLeases(int leaseTimeoutSeconds) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireBefore = now.minusSeconds(Math.max(leaseTimeoutSeconds, 1));
        int recovered = crawlUrlMapper.recoverExpiredLeases(expireBefore, now);
        if (recovered > 0) {
            // WARN 而非 INFO：这通常意味着有实例被杀或 OOM。偶发一次正常，
            // 反复出现必须查 —— 每次回收都意味着有一批 URL 的抓取工作被浪费了。
            log.warn("回收超时抓取租约 {} 条（租约 {} 秒）", recovered, leaseTimeoutSeconds);
        }
        return recovered;
    }

    @Override
    public int countFetched(Long seedId) {
        return seedId == null ? 0 : crawlUrlMapper.countFetchedBySeed(seedId);
    }

    @Override
    public int countPending(String domain) {
        return domain == null ? 0 : crawlUrlMapper.countPendingByDomain(domain);
    }

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
     * 退避秒数：{@code base * 2^(n-1)}，与 {@code kuma.crawl.retry-backoff-seconds} 同源。
     *
     * <p>服务端给了 {@code Retry-After} 时取两者较大值，而不是直接用它：
     * 对方说的 3 秒与我们的第 3 次退避（240 秒）哪个更该听，取决于这次失败是不是
     * 由「我们请求太快」引起的。取较大值在这两种情况下都不会显得更激进，
     * 而「更激进」是这里唯一要避免的方向。
     */
    private long backoffSeconds(int retryCount, Long retryAfterSeconds) {
        long base = Math.max(properties.getCrawl().getRetryBackoffSeconds(), 1);
        int shift = Math.min(retryCount - 1, 10);
        long local = base * (1L << shift);
        if (retryAfterSeconds == null) {
            return local;
        }
        return Math.max(local, retryAfterSeconds);
    }

    private boolean isTerminalStatus(int status) {
        return status == CrawlUrl.STATUS_DONE
                || status == CrawlUrl.STATUS_DEAD
                || status == CrawlUrl.STATUS_ROBOTS_DENIED
                || status == CrawlUrl.STATUS_SKIPPED;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
