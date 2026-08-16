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
    // 查询指定会话在请求时刻的不可变历史快照。
    public MemoryResponse getMemory(
            @PathVariable
            // 接收 URL 路径中的会话编号。
            String conversationId) {
        try {
            // 读取一次历史快照并与会话编号一起包装成稳定 JSON 结构。
            return new MemoryResponse(conversationId, memory.get(conversationId));
        } catch (RuntimeException error) {
            // 将存储边界异常转换为稳定服务端错误，避免泄漏实现细节和堆栈。
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "conversation memory unavailable", error);
        }
    }

    @DeleteMapping("/{conversationId}/memory")
    // 清空指定会话窗口，但不取消已经运行的 Agent 任务。
    public ClearMemoryResponse clearMemory(
            @PathVariable
            // 接收 URL 路径中的会话编号。
            String conversationId) {
        try {
            // 执行幂等清空并返回调用时是否确实存在窗口。
            return new ClearMemoryResponse(memory.clear(conversationId));
        } catch (RuntimeException error) {
            // 将清空时的存储故障转换为与查询一致的服务端错误边界。
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "conversation memory unavailable", error);
        }
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
