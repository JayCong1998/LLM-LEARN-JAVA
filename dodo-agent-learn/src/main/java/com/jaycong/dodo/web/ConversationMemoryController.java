// 将记忆管理控制器放在 Web 边界包中，避免 HTTP 协议进入记忆领域层。
package com.jaycong.dodo.web;

import com.jaycong.dodo.memory.ConversationMemory;
import com.jaycong.dodo.memory.ConversationTurn;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * 暴露跨请求会话记忆的只读查询与显式清空能力。
 * 控制器只转换 HTTP 请求和响应，窗口裁剪、快照及并发语义全部由 ConversationMemory 负责。
 */
@RestController
@RequestMapping("/api/agent/conversations")
// 定义会话记忆管理的 HTTP 边界。
public class ConversationMemoryController {

    // 保存抽象记忆端口，使控制器不依赖进程内或未来数据库实现。
    private final ConversationMemory memory;

    // 通过构造器显式注入与 Agent 共用的会话记忆实例。
    public ConversationMemoryController(ConversationMemory memory) {
        // 保存记忆端口供查询和清空接口调用。
        this.memory = memory;
    }

    @GetMapping("/{conversationId}/memory")
    // 查询指定会话在请求时刻的不可变历史快照，并将可能阻塞的存储操作隔离出事件循环。
    public Mono<MemoryResponse> getMemory(
            @PathVariable
            // 接收 URL 路径中的会话编号。
            String conversationId) {
        // 延迟到订阅发生后才读取记忆，使每次 HTTP 请求拥有独立的同步存储调用。
        return Mono.fromCallable(() ->
                        // 读取一次历史快照并与会话编号一起包装成稳定 JSON 结构。
                        new MemoryResponse(conversationId, memory.get(conversationId)))
                // JDBC 为阻塞 I/O，必须在线程池而非 Netty 事件循环中执行。
                .subscribeOn(Schedulers.boundedElastic())
                // 将存储边界异常转换为稳定服务端错误，避免泄漏实现细节和堆栈。
                .onErrorMap(RuntimeException.class, error ->
                        // 为前端保留既有 500 响应语义，并将底层异常作为原因保留给服务端日志。
                        new ResponseStatusException(INTERNAL_SERVER_ERROR, "conversation memory unavailable", error));
    }

    @DeleteMapping("/{conversationId}/memory")
    // 清空指定会话窗口但不取消已经运行的 Agent 任务，并隔离可能阻塞的数据库删除。
    public Mono<ClearMemoryResponse> clearMemory(
            @PathVariable
            // 接收 URL 路径中的会话编号。
            String conversationId) {
        // 延迟到订阅发生后才执行清空，使每次 DELETE 请求拥有独立的同步存储调用。
        return Mono.fromCallable(() ->
                        // 执行幂等清空并返回调用时是否确实存在窗口。
                        new ClearMemoryResponse(memory.clear(conversationId)))
                // JDBC 删除同样属于阻塞 I/O，必须在线程池中执行。
                .subscribeOn(Schedulers.boundedElastic())
                // 将清空时的存储故障转换为与查询一致的服务端错误边界。
                .onErrorMap(RuntimeException.class, error ->
                        // 为前端保留既有 500 响应语义，并将底层异常作为原因保留给服务端日志。
                        new ResponseStatusException(INTERNAL_SERVER_ERROR, "conversation memory unavailable", error));
    }

    // 使用不可变 DTO 表达查询接口的会话编号和有序轮次数组。
    public record MemoryResponse(
            // 保留请求中的会话编号，方便前端识别响应归属。
            String conversationId,
            // 保存查询时刻的不可变会话轮次快照。
            List<ConversationTurn> turns) {
    }

    // 使用不可变 DTO 表达清空操作是否删除了已有窗口。
    public record ClearMemoryResponse(
            // true 表示调用时存在窗口并已删除，false 表示原本不存在。
            boolean cleared) {
    }
}
