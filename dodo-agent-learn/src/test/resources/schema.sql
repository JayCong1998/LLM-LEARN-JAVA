-- 删除可能由前一个完整 Spring 上下文测试保留的 H2 测试表。
DROP TABLE IF EXISTS ai_session;
-- 创建与生产 ai_session 完整字段对应的 H2 测试表。
CREATE TABLE ai_session (
    -- 使用自增主键模拟生产 MySQL 的 id 列。
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 保存会话编号并要求每条记录都归属一个会话。
    session_id VARCHAR(255) NOT NULL,
    -- 保存用户问题长文本。
    question CLOB,
    -- 保存助手回答长文本。
    answer CLOB,
    -- 保存逗号分隔的工具名称。
    tools VARCHAR(1024),
    -- 保存首次响应耗时。
    first_response_time BIGINT,
    -- 保存总响应耗时。
    total_response_time BIGINT,
    -- 保存创建时间并让数据库在未显式传值时生成默认值。
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- 保存更新时间并让数据库在未显式传值时生成默认值。
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- 保存参考资料长文本。
    reference CLOB,
    -- 保存 Agent 类型。
    agent_type VARCHAR(255),
    -- 保存思考过程长文本。
    thinking CLOB,
    -- 保存关联文件编号。
    fileid VARCHAR(255),
    -- 保存推荐内容。
    recommend VARCHAR(1000)
);
