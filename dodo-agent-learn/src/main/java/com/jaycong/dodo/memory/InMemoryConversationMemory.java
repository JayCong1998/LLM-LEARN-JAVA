// 将进程内适配器放在记忆包中，作为 ConversationMemory 端口的首个实现。
package com.jaycong.dodo.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
// 使用按会话隔离的内存窗口保存最近完成的问答，应用重启后数据自然丢失。
public class InMemoryConversationMemory implements ConversationMemory {

    // 固定保留最近五轮完整问答，避免提示词随请求次数无限增长。
    private static final int MAX_TURNS = 5;
    // 使用并发映射隔离不同会话，并为同一键的追加与清空提供原子协调基础。
    private final ConcurrentMap<String, ConversationWindow> windows = new ConcurrentHashMap<>();

    @Override
    // 读取时返回独立快照，使后续追加或清空不会改变已经启动的 Agent 上下文。
    public List<ConversationTurn> get(String conversationId) {
        // 在访问映射前统一拒绝无效会话编号。
        validateConversationId(conversationId);
        // 获取当前映射中的窗口引用；读取操作不会为未知会话创建空窗口。
        ConversationWindow window = windows.get(conversationId);
        // 未保存过的会话以不可变空列表表达没有历史。
        if (window == null) {
            // 返回 JDK 提供的不可变空列表，防止调用者误改内部语义。
            return List.of();
        }
        // 由窗口在同步临界区中复制数据，避免读取到追加一半的状态。
        return window.snapshot();
    }

    @Override
    // 在同一会话键的原子计算中完成窗口创建和整轮追加。
    public void append(String conversationId, ConversationTurn turn) {
        // 在修改映射前统一拒绝无效会话编号。
        validateConversationId(conversationId);
        // 空轮次没有可保存内容，因此在进入并发结构前直接拒绝。
        if (turn == null) {
            // 使用参数异常向调用者明确指出缺少完整问答对象。
            throw new IllegalArgumentException("会话轮次不能为空");
        }
        /*
         * compute 会串行协调同一个 conversationId 上的 append 与 clear/remove：
         * 若清空先完成，本次追加会创建新窗口；若追加先完成，随后清空会删除包含该轮的窗口。
         * 这样不会发生线程拿到旧窗口、DELETE 移除它、线程又把新轮次写入孤立旧窗口的丢失更新。
         */
        windows.compute(conversationId, (key, existingWindow) -> {
            // 首次写入时创建窗口，已有会话则继续使用原窗口。
            ConversationWindow targetWindow = existingWindow == null ? new ConversationWindow() : existingWindow;
            // 在窗口内部同步追加并立即裁剪，保证外部永远看不到超过上限的稳定状态。
            targetWindow.appendAndTrim(turn);
            // 将完成更新的窗口保留在并发映射中。
            return targetWindow;
        });
    }

    @Override
    // 删除整个窗口以同时清除其中所有轮次，并返回是否确实存在历史容器。
    public boolean clear(String conversationId) {
        // 在修改映射前统一拒绝无效会话编号。
        validateConversationId(conversationId);
        // ConcurrentMap 对同一键的 remove 会与 compute 安全协调，非空结果表示发生删除。
        return windows.remove(conversationId) != null;
    }

    // 集中校验会话编号，保证三个端口方法采用完全一致的键约束。
    private void validateConversationId(String conversationId) {
        // 空引用、空串或纯空白都无法形成稳定的会话键。
        if (conversationId == null || conversationId.isBlank()) {
            // 使用参数异常阻止无效键进入存储边界。
            throw new IllegalArgumentException("会话编号不能为空");
        }
    }

    // 每个窗口独立持有锁，使不同 conversationId 的快照与追加无需争抢全局锁。
    private static final class ConversationWindow {

        // 双端队列支持在尾部追加最新轮次并从头部高效淘汰最旧轮次。
        private final Deque<ConversationTurn> turns = new ArrayDeque<>();

        // 将整轮问答的追加与超限裁剪放在同一临界区，维护 size <= MAX_TURNS 的不变量。
        private synchronized void appendAndTrim(ConversationTurn turn) {
            // 将最新成功问答追加到时间顺序尾部。
            turns.addLast(turn);
            // 防御性使用循环，即使未来窗口上限或批量逻辑改变也能恢复边界。
            while (turns.size() > MAX_TURNS) {
                // 每次从头部淘汰最旧的一轮完整问答。
                turns.removeFirst();
            }
        }

        // 在同一窗口锁下复制当前内容，提供时间点一致的不可变视图。
        private synchronized List<ConversationTurn> snapshot() {
            // 先复制队列，再包装为不可变列表，彻底隔离内部可变集合。
            return List.copyOf(new ArrayList<>(turns));
        }
    }
}
