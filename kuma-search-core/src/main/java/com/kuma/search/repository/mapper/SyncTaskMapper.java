package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.SyncTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同步任务表 Mapper：调度器读取启用任务，并在执行后回写位点与结果。
 */
@Mapper
public interface SyncTaskMapper extends BaseMapper<SyncTask> {
}
