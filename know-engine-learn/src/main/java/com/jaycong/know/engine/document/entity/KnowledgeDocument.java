package com.jaycong.know.engine.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jaycong.know.engine.common.base.BaseEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档表的实体映射。
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    /**
     * 文档标题。
     */
    private String docTitle;
    /**
     * 文档处理状态。
     */
    private String status;
    /**
     * 文档描述。
     */
    private String description;
    /**
     * 知识库类型。
     */
    private String knowledgeBaseType;
    /**
     * JSON 扩展字段。
     */
    private String extension;
}
