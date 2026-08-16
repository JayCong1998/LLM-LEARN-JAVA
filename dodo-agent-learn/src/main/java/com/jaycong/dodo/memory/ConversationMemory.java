// 将记忆端口放在领域包中，使 Agent 只依赖抽象而不绑定具体存储技术。
package com.jaycong.dodo.memory;

import java.util.List;

// 定义跨请求会话记忆必须提供的读取、追加和清空能力。
public interface ConversationMemory {

    // 返回指定会话在读取时刻的不可变历史快照。
    List<ConversationTurn> get(String conversationId);

    // 将一轮成功完成的问答原子追加到指定会话窗口。
    void append(String conversationId, ConversationTurn turn);

    // 清空指定会话，并返回清空前是否存在已保存的窗口。
    boolean clear(String conversationId);
}
