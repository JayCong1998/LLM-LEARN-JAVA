package com.jaycong.know.engine.document.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jaycong.know.engine.common.base.BaseEntity;
import com.jaycong.know.engine.document.constant.DocumentStatus;
import com.jaycong.know.engine.document.constant.KnowledgeBaseType;
import lombok.Data;

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
    private DocumentStatus status;
    /**
     * 原文档地址
     */
    private String docUrl;

    /**
     * 转换后的文档地址
     */
    private String convertedDocUrl;

    /**
     * 文档描述。
     */
    private String description;
    /**
     * 知识库类型
     */
    private KnowledgeBaseType knowledgeBaseType;
    /**
     * JSON 扩展字段。
     */
    private String extension;
}
