package com.kuma.search.sync;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 定时任务的分布式互斥锁。
 *
 * <h3>为什么每个定时任务都需要它</h3>
 * 定时任务在单实例部署时天然是互斥的，但一旦扩到两个实例，
 * 同一时刻两个实例都会触发同一个 {@code @Scheduled} 方法。对同步类任务而言后果很具体：
 * 两个实例同时把同一批数据往 ES 写，或者同时推进同一个同步水位 ——
 * 后者会导致水位跳跃、中间的数据永远不被同步。
 *
 * <h3>解锁为什么必须比对持有者标识</h3>
 * 朴素的 {@code tryLock → 干活 → del(key)} 有一个经典的误删场景：
 * 实例 A 拿锁后卡顿超过 TTL，锁自动过期，实例 B 拿到锁开始干活；
 * 此时 A 恢复并执行 finally 里的 del，把 <b>B 的锁</b>删掉了，
 * 于是实例 C 也能拿到锁 —— 互斥彻底失效。
 * 因此释放时用 Lua 脚本原子地校验"这把锁还是不是我的"，只有仍是才删。
 * 校验与删除必须原子，分成两次命令中间依然有窗口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLock {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 释放锁的 Lua 脚本。
     *
     * <p>读值与删除必须在 Redis 单线程内一次完成，否则校验通过到删除之间
     * 仍可能被其它实例抢走锁并重新赋值，导致误删。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    /**
     * 尝试获取锁。
     *
     * @param key 锁的 key，约定用 {@code CacheKeys.syncTaskLock(...)} 构造
     * @param ttl 锁的自动过期时间。<b>必须大于任务的最长预期执行时间</b>，
     *            否则会出现"任务还在跑锁已过期、另一个实例同时开跑"的情况
     * @return 持有者标识；为 null 表示没抢到锁，调用方应当直接跳过本次执行
     */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (Exception e) {
            // Redis 挂了时不能直接放行 —— 那会让所有实例同时开跑。
            // 返回 null（放弃本次执行）是更安全的选择：定时任务错过一次会在下个周期补上，
            // 而重复执行造成的数据错乱是无法自动修复的。
            log.warn("获取分布式锁失败，本次任务跳过，key={}，原因={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 释放锁。只有锁仍属于 {@code token} 对应的持有者时才真正删除。
     *
     * <p>释放失败不影响任务结果 —— 最多是这把锁等到 TTL 自然过期，
     * 代价是下一个周期的任务被跳过一次，不会造成数据问题。
     */
    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
        } catch (Exception e) {
            log.warn("释放分布式锁失败，等待其自然过期，key={}，原因={}", key, e.getMessage());
        }
    }
}
