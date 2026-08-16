// 将测试放在记忆包中，直接验证内存适配器对端口契约的实现。
package com.jaycong.dodo.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证进程内记忆的窗口边界、隔离性、快照安全和并发完整性。
class InMemoryConversationMemoryTest {

    @Test
    // 验证从未写入过的会话会返回稳定的空快照。
    void shouldReturnEmptySnapshotForUnknownConversation() {
        // 创建不依赖 Spring 容器的真实内存适配器。
        ConversationMemory memory = new InMemoryConversationMemory();

        // 断言未知会话没有任何历史轮次。
        assertTrue(memory.get("new-conversation").isEmpty());
    }

    @Test
    // 验证每个会话独立保存轮次并维持追加顺序。
    void shouldKeepConversationsIsolatedAndOrdered() {
        // 创建真实内存适配器以观察端到端存取行为。
        ConversationMemory memory = new InMemoryConversationMemory();
        // 创建会话一的第一轮问答。
        ConversationTurn first = new ConversationTurn("问题一", "回答一");
        // 创建会话一的第二轮问答。
        ConversationTurn second = new ConversationTurn("问题二", "回答二");
        // 创建另一个会话的独立问答。
        ConversationTurn other = new ConversationTurn("其他问题", "其他回答");

        // 按发生顺序向会话一追加两轮。
        memory.append("conversation-1", first);
        // 继续向同一会话追加第二轮。
        memory.append("conversation-1", second);
        // 向会话二追加一轮以验证键空间隔离。
        memory.append("conversation-2", other);

        // 断言会话一保持原始轮次顺序。
        assertEquals(List.of(first, second), memory.get("conversation-1"));
        // 断言会话二不会看到会话一的数据。
        assertEquals(List.of(other), memory.get("conversation-2"));
    }

    @Test
    // 验证第六轮到来时窗口只保留最近五轮完整问答。
    void shouldKeepOnlyLatestFiveTurns() {
        // 创建真实内存适配器以执行滑动窗口裁剪。
        ConversationMemory memory = new InMemoryConversationMemory();

        // 连续追加六轮以越过固定窗口上限。
        for (int index = 1; index <= 6; index++) {
            // 将轮次编号写入内容，便于准确识别被裁剪的最旧轮次。
            memory.append("conversation", new ConversationTurn("问题" + index, "回答" + index));
        }

        // 读取裁剪完成后的不可变历史快照。
        List<ConversationTurn> turns = memory.get("conversation");
        // 断言窗口始终限制为五个完整轮次。
        assertEquals(5, turns.size());
        // 断言最旧的第一轮已经被窗口淘汰。
        assertEquals("问题2", turns.getFirst().userContent());
        // 断言最新的第六轮仍位于快照尾部。
        assertEquals("问题6", turns.getLast().userContent());
    }

    @Test
    // 验证调用者不能通过返回列表修改存储内部状态。
    void shouldReturnImmutableSnapshot() {
        // 创建真实内存适配器。
        ConversationMemory memory = new InMemoryConversationMemory();
        // 保存一轮合法问答以获得非空快照。
        memory.append("conversation", new ConversationTurn("问题", "回答"));
        // 获取供调用者读取的历史副本。
        List<ConversationTurn> snapshot = memory.get("conversation");

        // 断言对快照的结构性修改会被拒绝。
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        // 断言失败的外部修改没有影响内部存储。
        assertEquals(1, memory.get("conversation").size());
    }

    @Test
    // 验证清空操作会删除整个会话窗口并准确返回是否发生删除。
    void shouldClearExistingConversationAndReportResult() {
        // 创建真实内存适配器。
        ConversationMemory memory = new InMemoryConversationMemory();
        // 先写入一轮，使会话窗口真实存在。
        memory.append("conversation", new ConversationTurn("问题", "回答"));

        // 断言第一次清空确实删除了已有窗口。
        assertTrue(memory.clear("conversation"));
        // 断言清空后再次读取会得到空快照。
        assertTrue(memory.get("conversation").isEmpty());
        // 断言第二次清空不存在的窗口时返回 false。
        assertFalse(memory.clear("conversation"));
    }

    @Test
    // 验证公共端口在入口处拒绝无法定位会话或缺少轮次的数据。
    void shouldRejectInvalidArguments() {
        // 创建真实内存适配器。
        ConversationMemory memory = new InMemoryConversationMemory();
        // 创建一轮合法问答，隔离本测试对会话编号的验证。
        ConversationTurn turn = new ConversationTurn("问题", "回答");

        // 断言读取时拒绝空会话编号。
        assertThrows(IllegalArgumentException.class, () -> memory.get(" "));
        // 断言追加时拒绝空引用会话编号。
        assertThrows(IllegalArgumentException.class, () -> memory.append(null, turn));
        // 断言追加时拒绝缺失的轮次对象。
        assertThrows(IllegalArgumentException.class, () -> memory.append("conversation", null));
        // 断言清空时同样执行统一的会话编号校验。
        assertThrows(IllegalArgumentException.class, () -> memory.clear(""));
    }

    @Test
    // 验证多个线程同时追加时不会破坏轮次对象或突破窗口上限。
    void shouldKeepWindowConsistentDuringConcurrentAppends() {
        // 创建真实内存适配器供所有工作线程共享。
        ConversationMemory memory = new InMemoryConversationMemory();
        // 使用固定大小线程池制造同一会话的并发写入竞争。
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            // 收集异步任务，确保断言前所有追加都已完成。
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            // 创建二十次并发追加，显著超过五轮窗口上限。
            for (int index = 0; index < 20; index++) {
                // 保存循环编号，避免异步任务读取变化中的循环变量。
                int turnNumber = index;
                // 在线程池中提交一次完整轮次追加，并保存其完成句柄。
                futures.add(CompletableFuture.runAsync(() -> memory.append(
                        // 所有任务使用同一会话编号，真实竞争同一个窗口。
                        "conversation",
                        // 每个任务写入独立且可验证的完整轮次。
                        new ConversationTurn("问题" + turnNumber, "回答" + turnNumber)), executor));
            }
            // 等待所有并发追加结束，并将线程内异常传播到当前测试。
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }

        // 读取所有写入完成后的稳定快照。
        List<ConversationTurn> turns = memory.get("conversation");
        // 断言并发场景下窗口仍严格保持五轮上限。
        assertEquals(5, turns.size());
        // 断言每个保留轮次仍包含配对完整的用户问题与助手回答。
        assertTrue(turns.stream().allMatch(turn -> turn.assistantContent()
                // 根据相同编号规则还原期望回答，识别被撕裂或串写的数据。
                .equals(turn.userContent().replace("问题", "回答"))));
    }
}
