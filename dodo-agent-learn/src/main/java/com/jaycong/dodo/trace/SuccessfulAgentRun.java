// 将成功 Agent 运行的稳定领域快照放在独立轨迹包中。
package com.jaycong.dodo.trace;

import java.util.List;

/**
 * 表示已经得到有效最终回答、可以整体写入持久化层的一次 Agent 运行。
 * 该记录故意不包含模型隐藏推理、工具参数和 Observation，防止它们越过运行边界。
 */
public record SuccessfulAgentRun(
        // 保存会话索引，使完整运行可以被会话记忆查询定位。
        String conversationId,
        // 保存本次用户问题，供后续模型上下文按问答回放。
        String question,
        // 保存非空最终回答，表示本次运行已经具备成功语义。
        String answer,
        // 保存按首次真实执行顺序去重后的工具名称，不保存工具参数和结果。
        List<String> executedToolNames,
        // 保存从工作线程开始到首次可观察事件的毫秒耗时。
        long firstResponseTimeMillis,
        // 保存从工作线程开始到最终答案就绪的毫秒耗时。
        long totalResponseTimeMillis,
        // 保存生成本记录的 Agent 类型，便于后续按实现方式观察。
        String agentType) {

    /**
     * 在不可变记录创建时一次性验证完整成功运行的必要条件。
     * 工具列表会被冻结，避免调用方在提交前后修改同一次运行的可观察轨迹。
     */
    public SuccessfulAgentRun {
        // 拒绝无法作为会话持久化索引的空白编号。
        validateNotBlank(conversationId, "会话编号不能为空");
        // 拒绝没有用户输入的伪造完整运行。
        validateNotBlank(question, "用户问题不能为空");
        // 拒绝尚未形成最终答案的半成品运行。
        validateNotBlank(answer, "最终回答不能为空");
        // 拒绝未来无法区分来源的空白 Agent 类型。
        validateNotBlank(agentType, "Agent 类型不能为空");
        // 首响应不可能发生在工作线程起点之前。
        if (firstResponseTimeMillis < 0L) {
            // 使用稳定异常文本帮助测试和调用方定位非法指标。
            throw new IllegalArgumentException("首响应耗时不能为负数");
        }
        // 最终答案就绪不可能发生在工作线程起点之前。
        if (totalResponseTimeMillis < 0L) {
            // 使用稳定异常文本帮助测试和调用方定位非法指标。
            throw new IllegalArgumentException("总响应耗时不能为负数");
        }
        // 拒绝缺失工具列表，要求直接回答明确使用空列表表达。
        if (executedToolNames == null) {
            // 阻止 null 在序列化时与空工具轨迹混淆。
            throw new IllegalArgumentException("工具名称列表不能为空");
        }
        // 创建不可修改快照，隔离构造后调用方对原列表的变更。
        executedToolNames = List.copyOf(executedToolNames);
    }

    // 集中校验所有必须以非空白文本表达的领域字段。
    private static void validateNotBlank(
            // 接收待校验的文本字段值。
            String value,
            // 接收对应字段的稳定错误消息。
            String message) {
        // 同时拒绝 null、空串和只含空白的文本。
        if (value == null || value.isBlank()) {
            // 使用参数异常阻止脏运行进入持久化端口。
            throw new IllegalArgumentException(message);
        }
    }
}
