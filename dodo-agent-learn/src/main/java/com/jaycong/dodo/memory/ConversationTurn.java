// 将会话轮次放在独立记忆包中，避免存储规则与 Agent 执行细节耦合。
package com.jaycong.dodo.memory;

// 用不可变记录表示一轮已经成功完成的用户问题与助手最终回答。
public record ConversationTurn(
        // 保存用户在这一轮发送的原始问题，供后续请求恢复上下文。
        String userContent,
        // 保存助手在这一轮生成的最终回答，不包含工具调用中间轨迹。
        String assistantContent) {

    // 在对象创建边界统一维护完整问答约束，阻止无效轮次进入任何记忆实现。
    public ConversationTurn {
        // 空白用户问题无法在后续请求中形成有效的 UserMessage，因此必须拒绝。
        if (userContent == null || userContent.isBlank()) {
            // 使用参数异常明确表示调用者提交了无效的领域数据。
            throw new IllegalArgumentException("用户问题不能为空");
        }
        // 空白助手回答不属于一次成功完成，不能被错误地保存为历史答案。
        if (assistantContent == null || assistantContent.isBlank()) {
            // 使用参数异常阻止不完整问答污染跨请求上下文。
            throw new IllegalArgumentException("助手回答不能为空");
        }
    }
}
