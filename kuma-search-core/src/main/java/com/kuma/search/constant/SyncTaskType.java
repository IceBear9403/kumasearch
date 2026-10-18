package com.kuma.search.constant;

import lombok.Getter;

/**
 * 同步任务类型：决定同步的扫描范围与断点续跑方式。
 *
 * <p>三种类型互补：增量负责日常低延迟追平，全量用于首次建库或索引损坏后的重建，
 * 对账则周期性抽样比对以发现增量链路漏掉的数据。区分它们是因为三者的失败恢复策略完全不同。
 */
@Getter
public enum SyncTaskType {

    /** 增量同步：按水位滚动扫描变更，代价小、频率高。 */
    INCREMENT("增量同步"),

    /** 全量同步：按批重建整索引，代价大，通常在类型启用或索引损坏时执行。 */
    FULL("全量同步"),

    /** 对账同步：比对 MySQL 与 ES 的差异并修补，作为最终一致性的兜底。 */
    RECONCILE("对账同步");

    /** 中文描述。 */
    private final String description;

    SyncTaskType(String description) {
        this.description = description;
    }
}
