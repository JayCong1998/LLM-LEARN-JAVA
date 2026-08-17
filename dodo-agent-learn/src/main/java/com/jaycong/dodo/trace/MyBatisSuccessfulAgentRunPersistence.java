// 将 MyBatis-Plus 完整运行写入适配器放在轨迹包中。
package com.jaycong.dodo.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaycong.dodo.memory.AiSessionEntity;
import com.jaycong.dodo.memory.AiSessionMapper;
import org.springframework.stereotype.Component;

/**
 * 将领域层确认成功的运行快照一次性转换为 ai_session 的一条记录。
 * 适配器只填充本阶段已定义语义的字段，绝不把隐藏推理或未来预留字段伪造成空数据。
 */
@Component
public class MyBatisSuccessfulAgentRunPersistence implements SuccessfulAgentRunPersistence {

    // 使用稳定 JSON 序列化器表达有序工具名列表。
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // 保存 MyBatis-Plus Mapper，使完整记录可以通过单次 insert 写入。
    private final AiSessionMapper aiSessionMapper;

    // 通过构造器注入 Mapper，保持领域端口与数据访问框架的边界清晰。
    public MyBatisSuccessfulAgentRunPersistence(AiSessionMapper aiSessionMapper) {
        // 拒绝缺少 Mapper 的错误装配，避免在成功终止时出现空指针。
        if (aiSessionMapper == null) {
            // 使用稳定参数异常帮助启动期定位缺失的数据访问依赖。
            throw new IllegalArgumentException("AiSessionMapper 不能为空");
        }
        // 保存已验证的 Mapper 供每次成功运行写入使用。
        this.aiSessionMapper = aiSessionMapper;
    }

    /**
     * 将已验证的完整运行在一次数据库插入中提交。
     * 若工具列表序列化失败，异常会阻止 insert，调用方因此不会发布成功 text 事件。
     */
    @Override
    public void persist(SuccessfulAgentRun run) {
        // 拒绝空运行快照，防止无业务语义的行进入会话表。
        if (run == null) {
            // 使用稳定异常文本帮助调用方定位持久化边界输入错误。
            throw new IllegalArgumentException("成功运行不能为空");
        }
        // 创建本次完整成功运行对应的数据库实体。
        AiSessionEntity entity = new AiSessionEntity();
        // 设置会话编号，使既有会话记忆查询可以定位本条问答。
        entity.setSessionId(run.conversationId());
        // 设置用户问题，使记忆回放保持原有 Question/Answer 模型。
        entity.setQuestion(run.question());
        // 设置已验证的最终回答，使成功记录不会成为半轮历史。
        entity.setAnswer(run.answer());
        // 将有序工具名写为安全 JSON 数组，不保存参数或 Observation。
        entity.setTools(toToolsJson(run.executedToolNames()));
        // 设置首次可观察事件的单调耗时毫秒数。
        entity.setFirstResponseTime(run.firstResponseTimeMillis());
        // 设置最终答案就绪前的总耗时毫秒数。
        entity.setTotalResponseTime(run.totalResponseTimeMillis());
        // 设置当前产生记录的 Agent 实现类型。
        entity.setAgentType(run.agentType());
        // 通过一次 Mapper 插入原子提交已填充的完整成功记录。
        aiSessionMapper.insert(entity);
    }

    // 将不可变工具名快照转换成数据库可长期保存的稳定 JSON 数组。
    private String toToolsJson(
            // 接收按真实执行顺序冻结的工具名称。
            java.util.List<String> toolNames) {
        try {
            // 使用 Jackson 保留数组顺序并让空列表稳定表示为 []。
            return OBJECT_MAPPER.writeValueAsString(toolNames);
        } catch (JsonProcessingException error) {
            // 序列化异常必须阻止 insert，避免形成没有正确轨迹格式的成功记录。
            throw new IllegalStateException("工具轨迹序列化失败", error);
        }
    }
}
