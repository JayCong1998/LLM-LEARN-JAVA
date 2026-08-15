package com.jaycong.dodo.agent;

/**
 * 浏览器与 Agent 之间的稳定事件协议。
 *
 * @param type    事件类型
 * @param content 事件内容
 */
public record AgentStreamEvent(String type, String content) {

    public static AgentStreamEvent text(String content) {
        return new AgentStreamEvent("text", content);
    }

    public static AgentStreamEvent error(String content) {
        return new AgentStreamEvent("error", content);
    }

    public static AgentStreamEvent complete() {
        return new AgentStreamEvent("complete", "");
    }
}
