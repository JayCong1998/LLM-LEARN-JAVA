// 将可安全返回给页面的运行轨迹投影放在轨迹领域包中。
package com.jaycong.dodo.trace;

import java.time.LocalDateTime;

// 只携带可观察元数据，故意不包含问答、推理、参数和 Observation。
public record AgentRunTrace(
        LocalDateTime createdAt,
        String tools,
        Long firstResponseTimeMillis,
        Long totalResponseTimeMillis,
        String agentType) {
}
