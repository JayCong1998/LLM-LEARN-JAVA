package com.jaycong.know.engine.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档表的数据访问对象。
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
