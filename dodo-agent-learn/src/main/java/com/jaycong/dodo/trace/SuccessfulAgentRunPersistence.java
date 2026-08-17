// 将完整成功运行的写入抽象放在轨迹领域包中。
package com.jaycong.dodo.trace;

/**
 * 定义一次性持久化完整成功运行的端口。
 * 端口没有读取能力，避免工具轨迹被误接入下一次模型上下文。
 */
public interface SuccessfulAgentRunPersistence {

    // 原子写入一条完整成功运行；异常表示调用方不得发布成功最终答案。
    void persist(SuccessfulAgentRun run);
}
