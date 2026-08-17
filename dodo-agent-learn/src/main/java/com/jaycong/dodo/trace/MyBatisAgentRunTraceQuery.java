// 将 MyBatis-Plus 运行轨迹查询适配器放在轨迹包中。
package com.jaycong.dodo.trace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jaycong.dodo.memory.AiSessionEntity;
import com.jaycong.dodo.memory.AiSessionMapper;
import org.springframework.stereotype.Component;

import java.util.List;

// 通过 ai_session 查询安全轨迹投影，而不让页面接触完整实体。
@Component
public class MyBatisAgentRunTraceQuery implements AgentRunTraceQuery {

    // 将查询窗口固定为十条，避免外部输入进入 SQL 尾部。
    private static final String RECENT_RUN_LIMIT = "LIMIT 10";
    // 保存实体 Mapper 以执行真实数据库查询。
    private final AiSessionMapper aiSessionMapper;

    // 注入 Mapper 以保持查询端口与 MyBatis 实现解耦。
    public MyBatisAgentRunTraceQuery(AiSessionMapper aiSessionMapper) {
        // 拒绝缺失 Mapper 的错误装配。
        if (aiSessionMapper == null) {
            // 以稳定异常指明缺少数据访问依赖。
            throw new IllegalArgumentException("AiSessionMapper 不能为空");
        }
        // 保存已验证 Mapper。
        this.aiSessionMapper = aiSessionMapper;
    }

    @Override
    // 查询当前会话最近十条成功运行，并仅映射页面允许展示的字段。
    public List<AgentRunTrace> getRecent(String conversationId) {
        // 拒绝空白会话编号进入数据库查询。
        if (conversationId == null || conversationId.isBlank()) {
            // 保持记忆端口一致的输入错误语义。
            throw new IllegalArgumentException("会话编号不能为空");
        }
        // 数据库按最新创建时间和主键读取固定窗口。
        return aiSessionMapper.selectList(Wrappers.lambdaQuery(AiSessionEntity.class)
                        // 限定当前会话。
                        .eq(AiSessionEntity::getSessionId, conversationId)
                        // 优先返回最新创建时间。
                        .orderByDesc(AiSessionEntity::getCreateTime)
                        // 同一时间时使用主键稳定排序。
                        .orderByDesc(AiSessionEntity::getId)
                        // 追加固定窗口限制。
                        .last(RECENT_RUN_LIMIT))
                // 将实体投影为无敏感字段的只读 DTO。
                .stream()
                // 不传递问题、回答、thinking 或工具细节。
                .map(row -> new AgentRunTrace(row.getCreateTime(), row.getTools(), row.getFirstResponseTime(), row.getTotalResponseTime(), row.getAgentType()))
                // 冻结查询快照。
                .toList();
    }
}
