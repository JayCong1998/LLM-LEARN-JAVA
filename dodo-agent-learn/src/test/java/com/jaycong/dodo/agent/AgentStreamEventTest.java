package com.jaycong.dodo.agent; // 将事件协议测试放在同包中，便于直接验证公开工厂与记录字段。

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamEventTest { // 定义 Agent 对外事件协议的单元测试集合。

    @Test
    void createsStableBasicEventsWithoutToolMetadata() { // 验证阶段一事件在协议扩展后仍保持原有语义。
        assertThat(AgentStreamEvent.text("hello")) // 创建携带最终文本的事件并开始结构断言。
                .isEqualTo(new AgentStreamEvent("text", "hello", null, null, null)); // 断言普通文本事件不携带任何工具元数据。
        assertThat(AgentStreamEvent.error("failed")) // 创建携带失败说明的事件并开始结构断言。
                .isEqualTo(new AgentStreamEvent("error", "failed", null, null, null)); // 断言错误事件只使用 type 和 content 字段。
        assertThat(AgentStreamEvent.complete()) // 创建协议级完成事件并开始结构断言。
                .isEqualTo(new AgentStreamEvent("complete", "", null, null, null)); // 断言完成事件使用空内容且不携带工具元数据。
    } // 结束基础事件兼容性测试。

    @Test
    void createsToolLifecycleEventsWithCorrelationMetadata() { // 验证工具开始和结束事件能够通过调用编号正确关联。
        assertThat(AgentStreamEvent.toolStart("weather", "call-1", "{\"city\":\"北京\"}")) // 创建工具开始事件并传入模型原始参数。
                .isEqualTo(new AgentStreamEvent( // 构造期望记录，逐字段固定工具开始协议。
                        "tool_start", // 工具开始事件使用独立类型，前端据此创建运行中卡片。
                        "", // 工具尚未产生 Observation，因此内容保持为空。
                        "weather", // 工具名称用于向学习者展示模型选择了哪个能力。
                        "call-1", // 调用编号用于把稍后的结束事件关联到同一张卡片。
                        "{\"city\":\"北京\"}")); // 原始 JSON 参数用于展示本次工具输入。
        assertThat(AgentStreamEvent.toolEnd("weather", "call-1", "北京：晴，25℃")) // 创建对应工具结束事件并传入 Observation。
                .isEqualTo(new AgentStreamEvent( // 构造期望记录，逐字段固定工具结束协议。
                        "tool_end", // 工具结束事件使用独立类型，前端据此更新已有卡片。
                        "北京：晴，25℃", // content 保存工具结果，供前端展示也供调试观察。
                        "weather", // 保留工具名称，使单个结束事件也具备自描述能力。
                        "call-1", // 保留相同调用编号，保证开始与结束可以稳定关联。
                        null)); // 工具结束事件无需重复发送已经展示过的输入参数。
    } // 结束工具生命周期协议测试。
} // 结束 Agent 事件协议测试类。
