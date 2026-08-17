package com.jaycong.dodo.agent; // 将单次 ReAct 可变状态集中在 Agent 核心包中。

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 保存一次 Agent 请求从创建到终止的全部可变状态。
 * 上下文不会注册为 Spring Bean；每次订阅必须创建新实例，避免不同会话共享消息、轮次或取消标记。
 */
public class ReactRunContext { // 定义单次运行的消息历史、循环防护和并发终止闸门。

    private final List<Message> messages; // 按实际发生顺序保存发送给模型的全部消息。
    private final int maxRounds; // 保存允许模型带工具决策的最大轮数。
    private final CharacterTokenBudget tokenBudget; // 保存只影响模型快照而不修改完整运行历史的固定预算器。
    private final Set<String> executedToolSignatures = new HashSet<>(); // 保存已经真实执行过的工具名称与参数签名。
    private final Set<String> executedToolNames = new LinkedHashSet<>(); // 按首次真实执行顺序保存安全的工具名称快照。
    private final AtomicLong firstResponseTimeMillis = new AtomicLong(-1L); // 保存首次可观察事件耗时，负值表示尚未产生。
    private final AtomicBoolean cancelled = new AtomicBoolean(); // 跨停止线程和模型工作线程共享取消状态。
    private final AtomicBoolean finished = new AtomicBoolean(); // 保护错误、完成和取消路径只发送一次终止序列。
    private UserMessage currentUserMessage; // 保存本轮显式当前问题对象以与同文本历史消息精确区分。
    private int round; // 记录已经成功开始的工具决策轮数，受同步方法保护。

    public ReactRunContext(List<Message> initialMessages, int maxRounds) { // 接收本轮初始上下文和明确的循环上限。
        this(initialMessages, maxRounds, new CharacterTokenBudget()); // 为生产运行固定装配默认两千 Token 预算器。
    } // 结束默认预算运行上下文构造方法。

    ReactRunContext(List<Message> initialMessages, int maxRounds, CharacterTokenBudget tokenBudget) { // 接收测试或后续配置所需的明确预算器。
        if (maxRounds < 1) { // 拒绝无法执行任何正常决策的无效配置。
            throw new IllegalArgumentException("maxRounds must be positive"); // 在启动任务前暴露配置错误。
        } // 结束最大轮次校验分支。
        this.messages = new ArrayList<>(initialMessages); // 复制初始消息，避免调用方随后修改内部历史。
        this.maxRounds = maxRounds; // 保存不可变轮次上限供每轮开始前检查。
        this.tokenBudget = tokenBudget; // 保存独立预算器以只在导出模型快照时执行裁剪。
    } // 结束 ReAct 运行上下文构造方法。

    public synchronized void addMessage(Message message) { // 串行追加一条系统、用户、助手或工具响应消息。
        messages.add(message); // 将消息放到历史尾部以保持模型对话时序。
    } // 释放上下文锁并结束消息追加方法。

    public synchronized List<Message> messages() { // 获取当前完整消息历史的只读快照。
        return List.copyOf(messages); // 复制并冻结列表，避免模型端或测试修改运行状态。
    } // 释放上下文锁并结束消息快照方法。

    public synchronized void setCurrentUserMessage(UserMessage currentUserMessage) { // 标记本轮 HTTP 请求对应且不可裁剪的用户问题对象。
        this.currentUserMessage = currentUserMessage; // 保存对象身份，避免相同文本的历史问题被误认为当前问题。
    } // 释放上下文锁并结束当前用户问题标记方法。

    public synchronized List<Message> messagesWithinBudget() { // 导出满足固定预算的模型调用快照而不改写完整运行历史。
        if (currentUserMessage == null) { // 初始化尚未标记当前问题时无法安全确定必需消息边界。
            throw new IllegalStateException("当前用户问题尚未初始化"); // 阻止模型在缺少当前问题语义的情况下调用。
        } // 结束当前问题初始化保护分支。
        return tokenBudget.messagesWithinBudget(messages, currentUserMessage); // 基于完整消息快照生成冻结的预算内模型输入。
    } // 释放上下文锁并结束预算消息快照方法。

    public synchronized boolean tryStartDecisionRound() { // 尝试在最大轮次范围内开始下一次允许工具的模型决策。
        if (round >= maxRounds) { // 已达到上限时拒绝第五次及之后的工具决策。
            return false; // 通知 Agent 改走关闭工具的强制收尾分支。
        } // 结束最大轮次保护分支。
        round++; // 只为实际允许开始的决策增加轮次计数。
        return true; // 通知调用方可以继续本轮工具决策。
    } // 释放上下文锁并结束轮次开始方法。

    public synchronized int round() { // 读取已经开始的工具决策轮数。
        return round; // 返回锁保护下的一致轮次快照。
    } // 释放上下文锁并结束轮次读取方法。

    public synchronized boolean markToolExecution(String toolName, String arguments) { // 登记工具签名并报告它是否首次出现。
        String normalizedArguments = arguments == null ? "" : arguments.trim(); // 只清理首尾空格，保留模型原始 JSON 的其他差异。
        String signature = toolName + "\n" + normalizedArguments; // 用不可与普通名称混淆的换行符连接名称和参数。
        boolean firstExecution = executedToolSignatures.add(signature); // Set 在首次加入时返回 true，重复签名返回 false。
        if (firstExecution) { // 只有真正进入执行路径的首次签名才应记录安全工具名称。
            executedToolNames.add(toolName); // 按调用首次发生的顺序保存名称，不保存参数或 Observation。
        } // 结束首次执行工具名称记录分支。
        return firstExecution; // 将重复保护结果返回给 Agent 决定是否实际调用注册表。
    } // 释放上下文锁并结束工具执行登记方法。

    public List<String> executedToolNames() { // 返回本次运行实际执行工具名称的不可变有序快照。
        synchronized (this) { // 与工具登记共用上下文锁，保证读取到一致的顺序集合。
            return List.copyOf(executedToolNames); // 冻结名称列表，防止持久化调用方反向修改运行上下文。
        } // 释放上下文锁并结束工具名称快照方法。
    } // 结束实际工具名称读取方法。

    public void recordFirstResponseTimeMillisIfAbsent(long elapsedMillis) { // 首次可观察事件到达时冻结一次性能指标。
        if (elapsedMillis < 0L) { // 单调时钟差值不应为负数，负值说明调用方违反计时契约。
            throw new IllegalArgumentException("首响应耗时不能为负数"); // 以稳定异常阻止非法指标继续传播。
        } // 结束首响应耗时校验分支。
        firstResponseTimeMillis.compareAndSet(-1L, elapsedMillis); // 只让第一个工具开始或最终文本获得首响应定义权。
    } // 结束首响应耗时冻结方法。

    public long firstResponseTimeMillis() { // 获取已经冻结的首次可观察事件耗时。
        return firstResponseTimeMillis.get(); // 返回原子快照；负值仍表示尚未产生可观察事件。
    } // 结束首响应耗时读取方法。

    public boolean markCancelled() { // 尝试将本次运行从未取消状态切换为已取消。
        return cancelled.compareAndSet(false, true); // 只让第一个取消来源获得成功结果并触发后续通知。
    } // 结束取消状态转换方法。

    public boolean isCancelled() { // 供阻塞循环在模型调用和工具执行边界轮询取消状态。
        return cancelled.get(); // 使用 AtomicBoolean 获取对其他线程最新写入可见的状态。
    } // 结束取消状态读取方法。

    public boolean tryFinish() { // 尝试取得本轮唯一的协议终止权。
        return finished.compareAndSet(false, true); // 只允许第一个完成、异常或取消路径发送终止事件。
    } // 结束完成闸门状态转换方法。

    public boolean isFinished() { // 查询本次运行是否已经完成协议收尾。
        return finished.get(); // 返回对所有竞争线程立即可见的完成状态。
    } // 结束完成状态读取方法。
} // 结束单次 ReAct 运行上下文定义。
