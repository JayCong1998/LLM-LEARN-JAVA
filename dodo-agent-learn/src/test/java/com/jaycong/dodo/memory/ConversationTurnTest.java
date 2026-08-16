// 将测试类放在记忆领域包中，便于直接表达会话轮次的领域约束。
package com.jaycong.dodo.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 验证跨请求记忆中一轮完整问答必须始终保持有效。
class ConversationTurnTest {

    @Test
    // 验证合法的用户问题与助手回答会被领域对象原样保存。
    void shouldKeepValidQuestionAndAnswer() {
        // 创建一轮合法问答，作为后续记忆窗口保存的最小数据单元。
        ConversationTurn turn = new ConversationTurn("我叫小明", "你好，小明");

        // 确认用户问题没有在构造过程中被意外修改。
        assertEquals("我叫小明", turn.userContent());
        // 确认助手最终回答没有在构造过程中被意外修改。
        assertEquals("你好，小明", turn.assistantContent());
    }

    @Test
    // 验证缺少用户问题时不能产生无法回放的会话轮次。
    void shouldRejectNullUserContent() {
        // 断言构造器拒绝空引用形式的用户问题。
        assertThrows(IllegalArgumentException.class, () -> new ConversationTurn(null, "回答"));
    }

    @Test
    // 验证纯空白用户问题不会被保存到跨请求记忆中。
    void shouldRejectBlankUserContent() {
        // 断言构造器拒绝只有空白字符的用户问题。
        assertThrows(IllegalArgumentException.class, () -> new ConversationTurn("   ", "回答"));
    }

    @Test
    // 验证缺少助手回答时不能产生不完整的成功轮次。
    void shouldRejectNullAssistantContent() {
        // 断言构造器拒绝空引用形式的助手回答。
        assertThrows(IllegalArgumentException.class, () -> new ConversationTurn("问题", null));
    }

    @Test
    // 验证纯空白助手回答不会被误认为成功结果写入记忆。
    void shouldRejectBlankAssistantContent() {
        // 断言构造器拒绝只有空白字符的助手回答。
        assertThrows(IllegalArgumentException.class, () -> new ConversationTurn("问题", "\t\n"));
    }
}
