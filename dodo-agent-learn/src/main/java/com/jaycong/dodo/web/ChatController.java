package com.jaycong.dodo.web; // 将控制器放在 Web 边界包中，避免 HTTP 细节进入 Agent 核心。

import com.jaycong.dodo.agent.AgentStreamEvent;
import com.jaycong.dodo.agent.ManualReactCallAgent;
import com.jaycong.dodo.react.JayCongReactAgent;
import com.jaycong.dodo.react.WebSearchMcpCreator;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
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

    private final ManualReactCallAgent callAgent; // 保存保留完整最终文本行为的对照 Agent。
    private final InMemoryTaskRegistry tasks; // 保存任务注册表，用于执行显式取消操作。

    @Autowired
    private ChatModel chatModel;

    public ChatController(ManualReactCallAgent callAgent, InMemoryTaskRegistry tasks) { // 通过构造器显式声明两种回答模式和停止依赖。
        this.callAgent = callAgent; // 保存仅用于对照接口的一次性文本 Agent 实例。
        this.tasks = tasks; // 保存与 Agent 共用的内存任务注册表实例。
    } // 结束控制器构造方法。

    @GetMapping(value = "/chat/call", produces = MediaType.TEXT_EVENT_STREAM_VALUE) // 保留旧一次性最终文本接口供后续学习对照。
    public Flux<ServerSentEvent<AgentStreamEvent>> call(@RequestParam String conversationId, @RequestParam String message) { // 接收与 stream 接口相同的请求参数。
        if (conversationId.isBlank() || message.isBlank()) { // 在创建对照 Agent 任务前同样拒绝空白输入。
            throw new ResponseStatusException(BAD_REQUEST, "conversationId and message must not be blank"); // 保持两个接口的 HTTP 参数错误语义一致。
        } // 结束对照接口参数校验分支。
        return callAgent.stream(conversationId, message).map(event -> ServerSentEvent.builder(event).event(event.type()).build()); // 将 call Agent 的事件转换为相同 SSE 帧格式。
    } // 结束一次性回答对照接口方法。

    @Autowired
    private WebSearchMcpCreator webSearchMcpCreator;

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE) // 保留旧一次性最终文本接口供后续学习对照。
    public Flux<ServerSentEvent<AgentStreamEvent>> stream(@RequestParam String conversationId, @RequestParam String message) { // 接收与 stream 接口相同的请求参数。
        JayCongReactAgent reactAgent = new JayCongReactAgent(chatModel,webSearchMcpCreator.getWebSearchToolCallbacks());
        return reactAgent.stream(conversationId, message)
                .map(event -> ServerSentEvent.builder(event).event(event.type()).build()); // 将 call Agent 的事件转换为相同 SSE 帧格式。
    } // 结束一次性回答对照接口方法。

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
