// 将 ai_session 的完整字段映射放在记忆包中，供当前会话记忆和后续运行轨迹阶段共用。
package com.jaycong.dodo.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

// 为实体生成标准访问器，使 Mapper、Lambda 条件构造器和测试能够安全访问字段。
@Getter
@Setter
// 将该实体绑定到已有的 ai_session 数据库表。
@TableName("ai_session")
// 完整表示 ai_session 的一行数据，而不将当前会话记忆阶段限制为三个字段。
public class AiSessionEntity {

    // 映射由数据库自增生成的会话记录主键。
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    // 映射跨 HTTP 请求识别同一对话的会话编号。
    private String sessionId;
    // 映射用户提交的原始问题长文本。
    private String question;
    // 映射 Agent 正常完成后的最终回答长文本。
    private String answer;
    // 映射本轮对话涉及的工具名称列表。
    private String tools;
    // 映射首次产生响应所花费的毫秒数。
    private Long firstResponseTime;
    // 映射完整回答流程所花费的毫秒数。
    private Long totalResponseTime;
    // 映射数据库记录创建时间，用于历史窗口的主排序。
    private LocalDateTime createTime;
    // 映射数据库记录最后更新时间，供后续更新型流程使用。
    private LocalDateTime updateTime;
    // 显式映射 reference 列，避免字段名在不同 SQL 方言中的歧义。
    @TableField("reference")
    private String reference;
    // 映射本轮执行所使用的 Agent 类型。
    private String agentType;
    // 映射模型或 Agent 产生的思考过程长文本。
    private String thinking;
    // 显式映射不遵循下划线命名的 fileid 列。
    @TableField("fileid")
    private String fileId;
    // 映射本轮对用户产生的推荐内容。
    private String recommend;
}
