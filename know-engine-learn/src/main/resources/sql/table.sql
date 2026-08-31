CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '文档ID',
  `doc_title` varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文档标题',
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态：INIT, UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED',
  `doc_url` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '该版本文档URL（MinIO原始文件）',
  `converted_doc_url` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '该版本转换后的文档URL',
  `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文档描述',
  `knowledge_base_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '知识库类型：DOCUMENT_SEARCH, DATA_QUERY',
  `extension` text COLLATE utf8mb4_unicode_ci COMMENT '扩展字段，保存JSON字符串',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档表';


CREATE TABLE `knowledge_segment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '片段ID',
  `text` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文本内容',
  `chunk_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '分片ID',
  `metadata` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '元数据',
  `document_id` bigint NOT NULL COMMENT '所属文档ID',
  `chunk_order` int NOT NULL COMMENT '顺序',
  `embedding_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '嵌入ID',
  `status` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态：STORED, VECTOR_STORED',
  `skip_embedding` int DEFAULT NULL COMMENT '是否跳过嵌入生成',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识片段表';


CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
  `nickname` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户显示名称',
  `email` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户电子邮箱',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `lock_version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_sys_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';
