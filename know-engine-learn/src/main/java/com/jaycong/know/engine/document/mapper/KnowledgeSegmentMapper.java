package com.jaycong.know.engine.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识片段表的数据访问对象。
 */
@Mapper
public interface KnowledgeSegmentMapper extends BaseMapper<KnowledgeSegment> {
}
