// 将运行轨迹 HTTP 查询边界放在 Web 包中。
package com.jaycong.dodo.web;

import com.jaycong.dodo.trace.AgentRunTrace;
import com.jaycong.dodo.trace.AgentRunTraceQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

// 暴露当前会话已完成运行的安全元数据，且不承担存储规则。
@RestController
@RequestMapping("/api/agent/conversations")
public class AgentRunTraceController {

    // 保存只读轨迹查询端口。
    private final AgentRunTraceQuery query;

    // 注入查询端口而非 Mapper，保持 HTTP 与数据访问解耦。
    public AgentRunTraceController(AgentRunTraceQuery query) {
        // 保存查询端口。
        this.query = query;
    }

    @GetMapping("/{conversationId}/runs")
    // 在阻塞数据库查询完成后返回稳定 JSON 包装对象。
    public Mono<RunTraceResponse> getRuns(@PathVariable String conversationId) {
        // 延迟执行同步 Mapper 查询，并将其移出 Netty 事件循环。
        return Mono.fromCallable(() -> new RunTraceResponse(query.getRecent(conversationId)))
                // MyBatis 底层 JDBC 阻塞，必须使用 boundedElastic。
                .subscribeOn(Schedulers.boundedElastic())
                // 统一映射为不会泄漏数据库实现细节的 500。
                .onErrorMap(RuntimeException.class, error -> new ResponseStatusException(INTERNAL_SERVER_ERROR, "agent run traces unavailable", error));
    }

    // 使用不可变响应对象表达当前会话的倒序轨迹列表。
    public record RunTraceResponse(List<AgentRunTrace> runs) {
    }
}
