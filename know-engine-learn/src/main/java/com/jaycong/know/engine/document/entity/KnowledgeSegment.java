package com.jaycong.know.engine.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jaycong.know.engine.common.base.BaseEntity;
import lombok.Data;

/**
 * 知识片段表的实体映射。
 */
@Data
@TableName("knowledge_segment")
public class KnowledgeSegment extends BaseEntity {

    /**
     * 片段文本内容。
     */
    private String text;
    /**
     * 业务分片标识。
     */
    private String chunkId;
    /**
     * JSON 元数据。
     */
    private String metadata;
    /**
     * 所属文档主键。
     */
    private Long documentId;
    /**
     * 文档内片段顺序。
     */
    private Integer chunkOrder;
    /**
     * 向量库嵌入标识。
     */
    private String embeddingId;
    /**
     * 片段处理状态。
     */
    private String status;
    /**
     * 是否跳过嵌入生成。
     */
    private Integer skipEmbedding;
}
