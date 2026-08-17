// 将 MySQL 持久化适配器放在记忆包中，使 Agent 继续只依赖 ConversationMemory 抽象。
package com.jaycong.dodo.memory;

import org.springframework.context.annotation.Primary;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 将此实现注册为 Spring Bean，并在同时存在内存实现时优先用于真实应用运行。
@Component
@Primary
// 使用已有 ai_session 表持久化成功问答，使会话记忆在应用重启后仍可恢复。
public class MySqlConversationMemory implements ConversationMemory {

    // 固定追加到查询尾部的窗口限制，不接受外部输入，因此不会形成 SQL 注入入口。
    private static final String RECENT_TURN_LIMIT = "LIMIT 5";
    // 保存 ai_session Mapper，以复用 MyBatis-Plus 的实体映射和通用 CRUD 能力。
    private final AiSessionMapper aiSessionMapper;

    // 通过构造器注入 Mapper，使适配器只依赖声明式的表访问边界。
    public MySqlConversationMemory(AiSessionMapper aiSessionMapper) {
        // 拒绝缺少数据访问端口的错误装配，避免运行时空指针隐藏配置问题。
        if (aiSessionMapper == null) {
            // 使用参数异常明确指出持久化适配器缺少必要 Mapper。
            throw new IllegalArgumentException("AiSessionMapper 不能为空");
        }
        // 保存已校验的 Mapper 供三个端口方法执行持久化操作。
        this.aiSessionMapper = aiSessionMapper;
    }

    @Override
    // 查询时返回与内存实现相同的不可变时间正序快照，供 Agent 按轮次回放提示词。
    public List<ConversationTurn> get(String conversationId) {
        // 在访问数据库前统一拒绝无法作为稳定键的会话编号。
        validateConversationId(conversationId);
        // 数据库按最新到最旧返回最多五条，避免把完整历史全部加载到 JVM。
        List<AiSessionEntity> newestFirstSessions = aiSessionMapper.selectList(
                // 使用 Lambda 字段引用构造会话条件和稳定排序，避免手写列名字符串。
                Wrappers.lambdaQuery(AiSessionEntity.class)
                        // 仅查询当前会话的历史记录。
                        .eq(AiSessionEntity::getSessionId, conversationId)
                        // 首先按创建时间倒序取得最新记录。
                        .orderByDesc(AiSessionEntity::getCreateTime)
                        // 创建时间相同时按自增主键倒序保持确定性。
                        .orderByDesc(AiSessionEntity::getId)
                        // 追加固定窗口限制，让数据库只返回最近五条。
                        .last(RECENT_TURN_LIMIT));
        // 将完整表实体投影为 Agent 上下文真正需要的用户问题和助手回答。
        List<ConversationTurn> chronologicalTurns = new ArrayList<>(newestFirstSessions.stream()
                // 每行记录恢复为不可分割的一轮领域问答。
                .map(session -> new ConversationTurn(session.getQuestion(), session.getAnswer()))
                // 先收集为可变列表，供后续反转操作恢复时间顺序。
                .toList());
        // 将最新优先恢复为最旧优先，保持提示词中的对话时间线自然连续。
        Collections.reverse(chronologicalTurns);
        // 返回不可变快照，避免调用方意外修改本次读取的历史内容。
        return List.copyOf(chronologicalTurns);
    }

    @Override
    // 只在 Agent 已成功产出最终回答后由调用方追加一整轮问答。
    public void append(String conversationId, ConversationTurn turn) {
        // 在写入数据库前统一拒绝无效会话编号。
        validateConversationId(conversationId);
        // 空轮次没有完整的问答对，不能形成可回放的持久化记录。
        if (turn == null) {
            // 以参数异常向调用方说明缺少必须保存的会话轮次。
            throw new IllegalArgumentException("会话轮次不能为空");
        }
        // 创建本阶段所需的表实体，其他字段保持空值并交由数据库默认值或后续阶段填充。
        AiSessionEntity session = new AiSessionEntity();
        // 设置当前会话的稳定编号。
        session.setSessionId(conversationId);
        // 设置本轮用户问题。
        session.setQuestion(turn.userContent());
        // 设置本轮最终助手回答。
        session.setAnswer(turn.assistantContent());
        // 通过 MyBatis-Plus 插入实体，参数绑定和主键回填由框架负责。
        aiSessionMapper.insert(session);
    }

    @Override
    // 删除指定会话的所有历史，并将受影响行数转换为端口约定的布尔结果。
    public boolean clear(String conversationId) {
        // 在执行删除前统一拒绝无效会话编号。
        validateConversationId(conversationId);
        // 非零受影响行数表示调用时确实存在并清除了至少一轮历史。
        return aiSessionMapper.delete(
                // 使用 Lambda 条件精确删除当前会话的全部记录。
                Wrappers.lambdaQuery(AiSessionEntity.class)
                        // 将删除范围限制为请求提供的会话编号。
                        .eq(AiSessionEntity::getSessionId, conversationId)) > 0;
    }

    // 集中维护三个端口方法共享的会话编号约束，保持与内存实现完全一致。
    private void validateConversationId(String conversationId) {
        // 空引用、空字符串和纯空白字符串都不能作为数据库会话键。
        if (conversationId == null || conversationId.isBlank()) {
            // 阻止无效键进入数据库查询或写入边界。
            throw new IllegalArgumentException("会话编号不能为空");
        }
    }
}
