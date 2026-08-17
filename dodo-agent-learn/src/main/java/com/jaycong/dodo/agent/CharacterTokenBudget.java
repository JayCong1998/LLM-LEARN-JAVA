package com.jaycong.dodo.agent; // 将上下文预算规则放在 Agent 核心包中，避免依赖 Web、数据库或 Spring 容器。

import org.springframework.ai.chat.messages.AssistantMessage; // 引入助手消息以读取工具调用元数据。
import org.springframework.ai.chat.messages.Message; // 引入统一消息接口以估算所有上下文元素。
import org.springframework.ai.chat.messages.SystemMessage; // 引入系统消息以识别不可裁剪提示。
import org.springframework.ai.chat.messages.ToolResponseMessage; // 引入工具响应消息以保持 Action 与 Observation 成组。
import org.springframework.ai.chat.messages.UserMessage; // 引入用户消息以定位本轮必须保留的问题。

import java.util.ArrayList; // 引入可变列表以构建候选消息组。
import java.util.Comparator; // 引入索引比较器以恢复原始对话顺序。
import java.util.List; // 引入列表类型以表达消息快照与原子分组。

/**
 * 用字符近似值在模型调用前限制上下文大小，避免把完整运行历史直接推入未知窗口大小的模型。
 * 此类不尝试替代模型专用 tokenizer；它只提供稳定、可测试的本地保护预算。
 */
public final class CharacterTokenBudget { // 定义无状态的上下文快照裁剪器。

    public static final int DEFAULT_MAX_ESTIMATED_TOKENS = 2_000; // 定义学习阶段约定的默认估算 Token 上限。
    private static final int MESSAGE_PROTOCOL_TOKENS = 4; // 为每条消息预留角色、边界等不可见协议开销。
    private static final int CHARACTERS_PER_ESTIMATED_TOKEN = 4; // 定义每四个字符近似折算为一个 Token 的固定规则。
    private final int maxEstimatedTokens; // 保存本实例用于比较的不可变预算上限。

    public CharacterTokenBudget() { // 创建使用学习阶段默认两千 Token 预算的裁剪器。
        this(DEFAULT_MAX_ESTIMATED_TOKENS); // 将默认值统一委托给可配置构造器以避免规则分叉。
    } // 结束默认预算构造器。

    CharacterTokenBudget(int maxEstimatedTokens) { // 接收测试或后续配置所需的明确预算上限。
        if (maxEstimatedTokens < 1) { // 拒绝无法容纳任何消息协议开销的非法预算。
            throw new IllegalArgumentException("上下文预算必须为正数"); // 在构造期尽早暴露配置错误。
        } // 结束预算合法性校验分支。
        this.maxEstimatedTokens = maxEstimatedTokens; // 保存已经验证的预算上限。
    } // 结束可配置预算构造器。

    public long estimate(Message message) { // 估算单条消息及其工具协议字段在上下文中占用的 Token 数。
        long characterCount = safeText(message.getText()).length(); // 先计入每种消息共有的可见文本内容。
        if (message instanceof AssistantMessage assistantMessage) { // 助手消息可能携带模型发出的工具调用参数。
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) { // 遍历每个调用以避免遗漏多工具决策的参数开销。
                characterCount += safeText(toolCall.id()).length(); // 计入调用编号以保留 Observation 关联语义。
                characterCount += safeText(toolCall.type()).length(); // 计入调用类型以近似工具协议负载。
                characterCount += safeText(toolCall.name()).length(); // 计入实际工具名称以反映可观察轨迹。
                characterCount += safeText(toolCall.arguments()).length(); // 计入原始参数，因为它们会随上下文发送给模型。
            } // 结束工具调用字段遍历。
        } // 结束助手工具调用估算分支。
        if (message instanceof ToolResponseMessage toolResponseMessage) { // 工具响应消息包含模型下一轮决策需要读取的 Observation。
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) { // 遍历聚合响应以计入全部工具结果。
                characterCount += safeText(response.id()).length(); // 计入响应关联的调用编号。
                characterCount += safeText(response.name()).length(); // 计入产生 Observation 的工具名称。
                characterCount += safeText(response.responseData()).length(); // 计入实际 Observation 文本。
            } // 结束工具响应字段遍历。
        } // 结束工具响应估算分支。
        return MESSAGE_PROTOCOL_TOKENS + divideAndRoundUp(characterCount, CHARACTERS_PER_ESTIMATED_TOKEN); // 返回协议固定成本与字符近似成本的总和。
    } // 结束单条消息估算方法。

    public List<Message> messagesWithinBudget(List<Message> messages, UserMessage currentUserMessage) { // 构造既满足预算又保留必需语义的模型消息快照。
        int systemIndex = findSystemIndex(messages); // 查找唯一必须保留的系统提示位置。
        int currentUserIndex = findCurrentUserIndex(messages, currentUserMessage); // 查找由本轮初始化明确标记的当前用户问题位置。
        long mandatoryTokens = estimate(messages.get(systemIndex)) + estimate(messages.get(currentUserIndex)); // 计算不可裁剪消息的最小成本。
        if (mandatoryTokens > maxEstimatedTokens) { // 系统提示和当前问题本身已无法放入固定窗口。
            throw new ContextBudgetExceededException(maxEstimatedTokens); // 拒绝模型调用，避免静默截断改变问题语义。
        } // 结束必需消息超预算分支。
        List<MessageGroup> groups = optionalGroups(messages, systemIndex, currentUserIndex); // 将剩余历史切分为不能被拆开的候选组。
        List<MessageGroup> retainedGroups = new ArrayList<>(); // 保存最终可进入模型快照的完整候选组。
        long usedTokens = mandatoryTokens; // 从不可裁剪消息成本开始累计已使用预算。
        for (int index = groups.size() - 1; index >= 0; index--) { // 从最新组向最旧组选择以优先保留最近上下文。
            MessageGroup group = groups.get(index); // 取得当前候选完整组。
            long groupTokens = group.estimatedTokens(); // 获取该组内消息的总估算成本。
            if (usedTokens + groupTokens <= maxEstimatedTokens) { // 只有整个组可以放入剩余预算时才接纳它。
                retainedGroups.add(group); // 原子保留该组，绝不只保留工具调用或 Observation 的一半。
                usedTokens += groupTokens; // 更新已经占用的预算。
            } // 结束单个候选组接纳分支。
        } // 结束从新到旧的候选选择循环。
        retainedGroups.sort(Comparator.comparingInt(MessageGroup::startIndex)); // 恢复选中消息在真实对话中的时间顺序。
        List<Message> snapshot = new ArrayList<>(); // 创建将要传给模型的独立快照。
        for (int index = 0; index < messages.size(); index++) { // 依原始消息顺序合成必需消息与选中完整组。
            if (index == systemIndex || index == currentUserIndex || belongsToRetainedGroup(index, retainedGroups)) { // 只复制系统、当前问题和完整选中组。
                snapshot.add(messages.get(index)); // 保留原消息对象以维持 Spring AI 消息类型与工具字段。
            } // 结束单条消息是否进入快照的判断。
        } // 结束快照合成循环。
        return List.copyOf(snapshot); // 冻结结果，防止模型端或调用方修改裁剪快照。
    } // 结束上下文预算裁剪方法。

    private List<MessageGroup> optionalGroups(List<Message> messages, int systemIndex, int currentUserIndex) { // 根据历史轮次和工具 Observation 边界构造可裁剪的原子组。
        List<MessageGroup> groups = new ArrayList<>(); // 保存按时间顺序产生的完整候选组。
        int index = 0; // 从最早消息开始扫描全部上下文。
        while (index < messages.size()) { // 逐条识别系统、当前问题、历史轮次与运行期工具组。
            if (index == systemIndex || index == currentUserIndex) { // 必需消息已经单独计入预算，不应进入可丢弃候选。
                index++; // 跳过当前必需消息并继续扫描后续消息。
            } else if (index < currentUserIndex && messages.get(index) instanceof UserMessage && index + 1 < currentUserIndex && messages.get(index + 1) instanceof AssistantMessage) { // 初始化的持久化历史以用户问题和最终回答成对出现。
                groups.add(group(messages, index, index + 1)); // 将一整轮历史作为不可拆分候选组。
                index += 2; // 跳过已经归组的用户与助手消息。
            } else if (messages.get(index) instanceof AssistantMessage assistantMessage && !assistantMessage.getToolCalls().isEmpty() && index + 1 < messages.size() && messages.get(index + 1) instanceof ToolResponseMessage) { // 模型 Action 后紧跟聚合 Observation 时必须成组。
                groups.add(group(messages, index, index + 1)); // 将工具调用和响应整体加入候选。
                index += 2; // 跳过已经归组的 Action 与 Observation。
            } else { // 其余普通助手消息、强制收尾提示或异常结构都按单条处理。
                groups.add(group(messages, index, index)); // 保留单条消息的独立裁剪边界。
                index++; // 移动到下一条尚未处理的消息。
            } // 结束当前消息的原子分组判断。
        } // 结束上下文扫描循环。
        return groups; // 返回保持原始时序的所有可选分组。
    } // 结束可选消息分组方法。

    private MessageGroup group(List<Message> messages, int startIndex, int endIndex) { // 为一段连续消息创建带预估成本的候选组。
        long estimatedTokens = 0L; // 初始化本组累计 Token 成本。
        for (int index = startIndex; index <= endIndex; index++) { // 遍历组内每条消息。
            estimatedTokens += estimate(messages.get(index)); // 累加每条消息含协议字段的估算成本。
        } // 结束组成本累计循环。
        return new MessageGroup(startIndex, endIndex, estimatedTokens); // 返回可供逆序选择的不可拆分组。
    } // 结束候选组创建方法。

    private int findSystemIndex(List<Message> messages) { // 定位本次 Agent 运行初始化写入的系统提示。
        for (int index = 0; index < messages.size(); index++) { // 从前向后扫描消息历史。
            if (messages.get(index) instanceof SystemMessage) { // 第一条系统消息即为本阶段约定的必需提示。
                return index; // 返回系统消息在原始列表中的位置。
            } // 结束系统消息类型判断。
        } // 结束系统消息扫描循环。
        throw new IllegalArgumentException("上下文缺少系统提示"); // 拒绝没有安全系统约束的模型调用快照。
    } // 结束系统消息定位方法。

    private int findCurrentUserIndex(List<Message> messages, UserMessage currentUserMessage) { // 用对象身份定位由当前运行显式标记的用户问题。
        for (int index = 0; index < messages.size(); index++) { // 从前向后扫描消息历史。
            if (messages.get(index) == currentUserMessage) { // 只接受同一对象，避免与历史中相同文本的问题混淆。
                return index; // 返回当前问题在原始列表中的位置。
            } // 结束当前问题身份判断。
        } // 结束当前问题扫描循环。
        throw new IllegalArgumentException("上下文缺少当前用户问题"); // 拒绝无法确定必需问题边界的调用。
    } // 结束当前用户问题定位方法。

    private boolean belongsToRetainedGroup(int messageIndex, List<MessageGroup> retainedGroups) { // 判断某条消息是否落在任一完整保留组内。
        return retainedGroups.stream().anyMatch(group -> messageIndex >= group.startIndex() && messageIndex <= group.endIndex()); // 用闭区间匹配组的连续消息范围。
    } // 结束保留组归属判断方法。

    private long divideAndRoundUp(long dividend, int divisor) { // 计算非负字符数除以固定字符密度后的向上取整值。
        return (dividend + divisor - 1L) / divisor; // 通过整数运算避免浮点误差并正确处理零字符消息。
    } // 结束向上取整除法。

    private String safeText(String text) { // 将可能为 null 的消息协议字段统一视为空字符串。
        return text == null ? "" : text; // 防止异常工具字段破坏预算保护本身。
    } // 结束空文本归一化方法。

    private record MessageGroup(int startIndex, int endIndex, long estimatedTokens) { // 表示必须整体保留或整体丢弃的一段连续消息。
    } // 结束消息原子组记录定义。

    public static final class ContextBudgetExceededException extends RuntimeException { // 定义供 Agent 转换为稳定 SSE error 的专用预算异常。

        public ContextBudgetExceededException(int maxEstimatedTokens) { // 根据实际配置的固定上限创建面向用户的稳定错误信息。
            super("上下文预算不足：系统提示和当前问题已超过 " + maxEstimatedTokens + " Token"); // 明确说明未发送模型调用的拒绝原因。
        } // 结束预算异常构造器。
    } // 结束预算异常定义。
} // 结束字符近似 Token 预算器定义。
