package com.jaycong.dodo.web; // 将控制器放在 Web 边界包中，避免 HTTP 细节进入 Agent 核心。

import com.jaycong.dodo.agent.AgentStreamEvent;
import com.jaycong.dodo.agent.StreamingChatAgent;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * 暴露最小 Agent 的 HTTP 接口。
 * 控制器只处理协议转换和参数校验，不承载模型调用与任务生命周期规则。
 */
@RestController
@RequestMapping("/api/agent")
public class ChatController { // 定义流式对话和任务停止两个 HTTP 边界操作。

    private final StreamingChatAgent agent; // 保存 Agent 服务，用于创建对话输出流。
    private final InMemoryTaskRegistry tasks; // 保存任务注册表，用于执行显式取消操作。

    public ChatController(StreamingChatAgent agent, InMemoryTaskRegistry tasks) { // 通过构造器显式声明控制器依赖。
        this.agent = agent; // 保存 Spring 注入的流式 Agent 实例。
        this.tasks = tasks; // 保存与 Agent 共用的内存任务注册表实例。
    } // 结束控制器构造方法。

    /**
     * 建立一次 SSE 对话流。
     * Controller 只负责 HTTP 边界，实际任务生命周期由 StreamingChatAgent 管理。
     *
     * @param conversationId 会话唯一编号，同时也是任务并发控制和停止操作的索引
     * @param message        本轮发送给 Agent 的用户消息
     * @return 持续输出 Agent 事件的 SSE 响应流
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamEvent>> stream( // 返回多事件 Flux，使 WebFlux 可以逐个写出 SSE 帧。
            @RequestParam
            String conversationId, // 从查询字符串读取会话编号，并要求请求必须提供该参数。
            @RequestParam
            String message) { // 从查询字符串读取用户消息，并结束方法参数列表。
        if (conversationId.isBlank() || message.isBlank()) { // 在创建 Agent 任务前拒绝空白会话编号或空白消息。
            throw new ResponseStatusException( // 抛出 WebFlux 可识别的 HTTP 状态异常，中止本次请求。
                    BAD_REQUEST, // 把非法输入映射为 HTTP 400，而不是内部服务器错误。
                    "conversationId and message must not be blank"); // 提供稳定的参数错误说明并结束异常构造。
        } // 结束请求参数校验分支。
        return agent.stream(conversationId, message) // 把合法参数交给 Agent，取得与传输协议无关的事件流。
                .map(event -> ServerSentEvent.builder(event) // 把每个 Agent 事件包装为一个 SSE 帧，并保留原对象作为 data。
                        .event(event.type()) // 同步设置 SSE 的 event 字段，方便客户端按事件类型识别消息。
                        .build()); // 构建不可变 SSE 对象，并结束事件映射链。
    } // 结束流式对话接口方法。

    /**
     * 根据会话编号停止一个正在运行的 Agent 任务。
     *
     * @param conversationId 路径中携带的目标会话编号
     * @return stopped 表示调用时是否确实存在并取消了对应任务
     */
    @PostMapping("/tasks/{conversationId}/stop")
    public StopResponse stop( // 从路径中提取会话编号并执行停止请求。
            @PathVariable
            String conversationId) { // 接收路径中的目标会话编号，并结束方法参数列表。
        return new StopResponse(tasks.cancel(conversationId)); // 取消任务，并把布尔结果包装成稳定的 JSON 响应。
    } // 结束任务停止接口方法。

    public record StopResponse(boolean stopped) { // 使用不可变 record 表达停止接口的唯一响应字段。
    } // 结束停止响应记录类型；record 自动生成访问器和序列化所需结构。
} // 结束 Agent HTTP 控制器定义。
