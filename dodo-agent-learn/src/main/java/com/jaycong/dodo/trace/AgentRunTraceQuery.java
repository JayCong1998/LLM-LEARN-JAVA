// 将运行轨迹查询端口放在轨迹领域包中。
package com.jaycong.dodo.trace;

import java.util.List;

// 定义仅按会话读取安全运行元数据的查询能力。
public interface AgentRunTraceQuery {

    // 返回目标会话最近成功运行的倒序不可变快照。
    List<AgentRunTrace> getRecent(String conversationId);
}
