package com.kuma.search.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 *
 * <p>要点：
 * <ul>
 *   <li><b>key 用字符串序列化器</b>。默认的 JDK 序列化会把 key 变成带二进制前缀的字节串，
 *       用 redis-cli 排查时完全不可读，也让「按 key 前缀批量清理」无法实现。</li>
 *   <li><b>value 用 JSON</b>并写入类型信息，使缓存对象能原样还原，同时保持可读性。</li>
 *   <li>额外提供一个 {@link RedisMessageListenerContainer}：这是<b>二级缓存能在多实例下正确工作</b>
 *       的前提 —— L1 是进程内的 Caffeine，一个实例更新了数据，其它实例的 L1 必须靠广播来失效，
 *       否则会长期返回旧值。详见 {@code TwoLevelCacheService}。</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * 通用 RedisTemplate。
     *
     * <p>注意这里<b>单独构造</b>了一个 ObjectMapper 而不是复用 Spring 容器里的那个：
     * 缓存值需要写入类型信息（{@code @class}）才能反序列化回原类型，
     * 而接口层的 ObjectMapper 不应该被污染成带类型信息的输出（那会让 API 响应里多出 {@code @class} 字段）。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        ObjectMapper cacheMapper = new ObjectMapper();
        cacheMapper.registerModule(new JavaTimeModule());
        cacheMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        cacheMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(cacheMapper);
        StringRedisSerializer keySerializer = new StringRedisSerializer();

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 发布/订阅容器，用于 L1 本地缓存的跨实例失效广播。
     *
     * <p>没有它，二级缓存在多实例部署下就是错的：实例 A 更新了数据并清了 Redis，
     * 实例 B 的 Caffeine 里仍留着旧值，直到 TTL 到期。把 L1 的 TTL 设短只能缩小窗口，不能消除。
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * 字符串专用模板。热词榜（ZSET）、搜索历史（LIST/ZSET）、分布式锁等
     * 结构化操作用它比泛型模板更直接，也避免值序列化器带来的额外开销。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
