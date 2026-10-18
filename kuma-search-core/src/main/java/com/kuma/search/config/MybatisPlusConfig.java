package com.kuma.search.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置。
 *
 * <p>为什么选 MyBatis-Plus 而不是 JPA：本项目的同步模块需要写「按 update_time + id 双游标
 * 扫描增量」这类查询，MyBatis 的显式 SQL 比 JPA 的 Criteria/派生查询更直接可控，
 * 也更容易在压测时看清实际执行的语句。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 插件链。注意顺序有意义：分页插件必须在内层，否则会被外层插件的改写影响。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 防止全表更新/删除：没有 where 条件的 update/delete 直接抛异常。
        // 这是管理后台这类带「批量操作」的接口最容易出的生产事故。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限，与 kuma.elasticsearch.max-page-size 保持一致的量级，
        // 避免管理后台一次拉几十万行把应用内存打满。
        pagination.setMaxLimit(500L);
        // 溢出总页数后不回到首页，而是返回空结果 —— 回到首页会让前端分页器产生诡异的跳转。
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }

    /**
     * 自动填充 create_time / update_time。
     *
     * <p>放在这里而不是靠数据库的 DEFAULT CURRENT_TIMESTAMP，是因为
     * <b>增量同步依赖 update_time 作为水位线</b>：如果时间由数据库生成，
     * 应用侧拿到实体时它的值还是 null，写 Outbox 事件时就无法带上准确的水位。
     */
    @Component
    public static class AutoFillHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now();
            this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
            this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        }
    }
}
