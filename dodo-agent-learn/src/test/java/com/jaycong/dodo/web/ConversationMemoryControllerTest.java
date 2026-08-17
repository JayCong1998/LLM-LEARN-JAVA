// 将记忆管理接口测试放在 Web 边界包中，验证 HTTP 协议而非存储内部实现。
package com.jaycong.dodo.web;

import com.jaycong.dodo.memory.ConversationMemory;
import com.jaycong.dodo.memory.ConversationTurn;
import com.jaycong.dodo.memory.InMemoryConversationMemory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// 只加载记忆控制器及测试存储，快速验证 GET 和 DELETE 的 JSON 契约。
@WebFluxTest(ConversationMemoryController.class)
@Import(ConversationMemoryControllerTest.MemoryTestConfiguration.class)
class ConversationMemoryControllerTest {

    @Autowired
    // 注入 WebFlux 测试客户端，用真实路由和 JSON 编解码执行请求。
    private WebTestClient client;
    @Autowired
    // 注入与控制器共享的可配置测试记忆，便于安排接口前置数据和故障。
    private ConfigurableConversationMemory memory;

    @Test
    // 验证 GET 返回会话编号和按顺序保存的完整问答数组。
    void shouldReturnConversationMemory() {
        // 为目标会话保存第一轮问答。
        memory.append("conversation-get", new ConversationTurn("问题一", "回答一"));
        // 为目标会话保存第二轮问答。
        memory.append("conversation-get", new ConversationTurn("问题二", "回答二"));

        // 请求目标会话的跨请求记忆。
        client.get()
                // 使用设计约定的会话记忆查询路径。
                .uri("/api/agent/conversations/conversation-get/memory")
                // 执行 HTTP 请求。
                .exchange()
                // 断言查询成功。
                .expectStatus().isOk()
                // 断言 JSON 中保留目标会话编号。
                .expectBody()
                // 断言顶层 conversationId 字段正确。
                .jsonPath("$.conversationId").isEqualTo("conversation-get")
                // 断言两轮历史均被返回。
                .jsonPath("$.turns.length()").isEqualTo(2)
                // 断言第一轮用户问题顺序正确。
                .jsonPath("$.turns[0].userContent").isEqualTo("问题一")
                // 断言第一轮助手回答与问题配对。
                .jsonPath("$.turns[0].assistantContent").isEqualTo("回答一")
                // 断言第二轮用户问题顺序正确。
                .jsonPath("$.turns[1].userContent").isEqualTo("问题二")
                // 断言第二轮助手回答与问题配对。
                .jsonPath("$.turns[1].assistantContent").isEqualTo("回答二");
    }

    @Test
    // 验证未知会话以空数组响应，而不是返回 404 或 null。
    void shouldReturnEmptyTurnsForUnknownConversation() {
        // 查询一个从未写入的会话编号。
        client.get()
                // 使用未知会话构造合法记忆路径。
                .uri("/api/agent/conversations/unknown-conversation/memory")
                // 执行 HTTP 请求。
                .exchange()
                // 断言未知会话仍是成功查询。
                .expectStatus().isOk()
                // 开始检查 JSON 响应体。
                .expectBody()
                // 断言响应保留调用方提供的会话编号。
                .jsonPath("$.conversationId").isEqualTo("unknown-conversation")
                // 断言没有历史时使用空数组表达。
                .jsonPath("$.turns.length()").isEqualTo(0);
    }

    @Test
    // 验证 DELETE 清空已有窗口，并用 cleared 准确表达删除结果。
    void shouldClearConversationMemoryAndReportWhetherItExisted() {
        // 预先保存一轮，使第一次清空存在真实目标。
        memory.append("conversation-delete", new ConversationTurn("问题", "回答"));

        // 第一次请求删除已有会话窗口。
        client.delete()
                // 使用设计约定的记忆清空路径。
                .uri("/api/agent/conversations/conversation-delete/memory")
                // 执行 DELETE 请求。
                .exchange()
                // 断言清空操作成功响应。
                .expectStatus().isOk()
                // 检查第一次清空结果。
                .expectBody()
                // 断言已有窗口确实被删除。
                .jsonPath("$.cleared").isEqualTo(true);
        // 第二次请求删除已经不存在的同一会话窗口。
        client.delete()
                // 继续使用相同会话路径验证幂等语义。
                .uri("/api/agent/conversations/conversation-delete/memory")
                // 执行第二次 DELETE 请求。
                .exchange()
                // 断言幂等清空仍返回 HTTP 200。
                .expectStatus().isOk()
                // 检查第二次清空结果。
                .expectBody()
                // 断言没有窗口可删时 cleared 为 false。
                .jsonPath("$.cleared").isEqualTo(false);
    }

    @Test
    // 验证底层记忆异常被控制器转换成稳定的服务端错误状态。
    void shouldReturnServerErrorWhenMemoryIsUnavailable() {
        // 请求测试记忆约定会抛出异常的特殊会话编号。
        client.get()
                // 使用故障会话路径触发读取异常。
                .uri("/api/agent/conversations/failing-conversation/memory")
                // 执行 HTTP 请求。
                .exchange()
                // 断言存储故障被映射为 HTTP 500。
                .expectStatus().is5xxServerError();
    }

    @Test
    // 验证未来 JDBC 存储的同步读取不会阻塞承载 HTTP 请求的 WebFlux 事件循环。
    void shouldReadMemoryOnBoundedElasticScheduler() {
        // 清空上一次测试可能记录的读取线程名称。
        memory.clearReadThreadName();
        // 发起一次正常记忆查询以触发底层 get 调用。
        client.get()
                // 使用独立会话编号避免影响其他接口断言。
                .uri("/api/agent/conversations/thread-check/memory")
                // 执行 HTTP 请求并等待响应完成。
                .exchange()
                // 断言正常读取仍返回成功状态。
                .expectStatus().isOk();
        // 断言同步存储访问已被调度到专门承载阻塞任务的线程池。
        assertThat(memory.readThreadName()).contains("boundedElastic");
    }

    @TestConfiguration(proxyBeanMethods = false)
    // 提供仅供 WebFlux 切片测试使用的可控记忆 Bean。
    static class MemoryTestConfiguration {

        @Bean
        // 创建控制器和测试类共享的可配置记忆实例。
        ConfigurableConversationMemory conversationMemory() {
            // 返回基于真实内存适配器的故障注入包装器。
            return new ConfigurableConversationMemory();
        }
    }

    // 使用真实窗口语义保存数据，仅为特殊编号注入读取故障。
    static final class ConfigurableConversationMemory implements ConversationMemory {
        // 委托生产内存适配器完成正常场景的读写和清空。
        private final ConversationMemory delegate = new InMemoryConversationMemory();
        // 保存最近一次读取所在的线程名称，供线程边界测试断言。
        private final AtomicReference<String> readThreadName = new AtomicReference<>();

        @Override
        // 读取正常会话历史，并为特殊编号模拟存储不可用。
        public List<ConversationTurn> get(String conversationId) {
            // 在调用委托前记录当前执行线程，用于确认控制器是否隔离阻塞 I/O。
            readThreadName.set(Thread.currentThread().getName());
            // 识别测试专用故障会话编号。
            if ("failing-conversation".equals(conversationId)) {
                // 抛出底层存储异常，验证控制器的错误边界。
                throw new IllegalStateException("memory unavailable");
            }
            // 正常会话委托真实内存适配器返回快照。
            return delegate.get(conversationId);
        }

        // 清空线程记录，使每次线程边界测试只检查自己触发的读取。
        private void clearReadThreadName() {
            // 将记录恢复为空值，避免沿用前一个测试的执行线程。
            readThreadName.set(null);
        }

        // 返回最近一次读取线程名称供测试断言。
        private String readThreadName() {
            // 获取原子引用中的最新线程名称快照。
            return readThreadName.get();
        }

        @Override
        // 将测试前置轮次交给真实内存适配器保存。
        public void append(String conversationId, ConversationTurn turn) {
            // 委托完成线程安全追加和窗口裁剪。
            delegate.append(conversationId, turn);
        }

        @Override
        // 将清空请求交给真实内存适配器执行。
        public boolean clear(String conversationId) {
            // 返回真实适配器的窗口删除结果。
            return delegate.clear(conversationId);
        }
    }
}
