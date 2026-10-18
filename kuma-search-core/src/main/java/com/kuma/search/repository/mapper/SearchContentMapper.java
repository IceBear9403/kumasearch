package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SearchContent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 检索内容表 Mapper：内容 CRUD，并为索引全量/增量重建提供分页扫描入口。
 */
@Mapper
public interface SearchContentMapper extends BaseMapper<SearchContent> {

    /**
     * 把一条被逻辑删除的内容行「复活」为可用的行（{@code deleted: 1 → 0}）。
     *
     * <h3>为什么需要这个方法</h3>
     * {@code t_search_content} 的唯一键是 {@code uk_type_biz (type_code, biz_id)}，<b>不含 deleted</b>。
     * 而删除是逻辑删除：行还在，只是 {@code deleted=1}。于是「删了再写同一个 biz_id」会这样失败：
     * <ol>
     *   <li>{@code selectOne} 查活行查不到 —— MyBatis-Plus 的 {@code @TableLogic} 把已删行过滤掉了；</li>
     *   <li>于是走 INSERT，撞上唯一键（已删的那一行还占着位置）；</li>
     *   <li>兜底分支重新查活行，还是查不到 —— 最后抛出
     *       {@code DB_ERROR「内容写入冲突且无法定位已有记录」}，一句完全指不到问题的话。</li>
     * </ol>
     * 这条路径不是边角情况：采集页被删后重抓同一个 URL（{@code biz_id = url_hash}）、
     * 投稿撤回后重新提交、管理员删掉再重录同一业务主键，都会走到它。
     *
     * <p>所以修法是把「已删的行」重新变成「可写的行」，而不是给唯一键加 {@code deleted}：
     * {@code (type_code, biz_id, deleted)} 只容得下<b>一次</b>删除 —— 第二次删除会插出第二行
     * {@code deleted=1} 并再次冲突。内容侧必须支持无限次删除/重建，见 {@code sql/schema.sql}
     * 里 {@code uk_type_biz} 的注释。
     *
     * <p>用注解式原句而不是 {@code updateById}：{@code @TableLogic} 会给实体更新自动补上
     * {@code deleted=0} 条件，用它反而改不动已删的行。
     *
     * <p>{@code update_time} 必须一起刷新：它是增量同步的第一列游标，
     * 复活后不刷新的话这次写入不会被增量任务看见。
     *
     * @return 实际复活的行数（0 表示没有已删的同业务主键行，正常走 INSERT）
     */
    @Update("UPDATE t_search_content SET deleted = 0, update_time = NOW() "
            + "WHERE type_code = #{typeCode} AND biz_id = #{bizId} AND deleted = 1")
    int reviveDeleted(@Param("typeCode") String typeCode, @Param("bizId") String bizId);

    /**
     * 该类型下未删除的内容条数。
     *
     * <p>供「删除类型配置」的前置检查使用：还有内容的类型不允许被删掉配置
     * （否则那些内容既搜不到、也删不掉，因为两条路径都要先解析类型定义）。
     */
    @Select("SELECT COUNT(*) FROM t_search_content "
            + "WHERE type_code = #{typeCode} AND deleted = 0")
    long countAliveByType(@Param("typeCode") String typeCode);
}
