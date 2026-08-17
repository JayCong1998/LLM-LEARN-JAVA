package com.jaycong.dodo.tool; // 将每工具熔断状态机放在工具包中，避免依赖 Agent 或 Web 细节。

import java.util.concurrent.ConcurrentHashMap; // 引入按工具名隔离状态的并发映射。
import java.util.concurrent.ConcurrentMap; // 引入并发映射接口以表达线程安全访问。
import java.util.function.LongSupplier; // 引入可替换单调时钟，支持确定性测试。

/**
 * 在进程内按工具名维护连续最终失败、打开窗口和半开探测状态。
 * 单个工具状态在自身锁内转换，避免并发请求在熔断到期时绕过“只允许一次探测”的保护。
 */
public class ToolCircuitBreaker { // 定义不保存参数且不依赖 Spring 的工具熔断状态机。

    private static final int FAILURE_THRESHOLD = 3; // 定义打开熔断所需的连续最终失败次数。
    private static final long OPEN_DURATION_MILLIS = 30_000L; // 定义熔断打开后拒绝调用的固定窗口。
    private final ConcurrentMap<String, ToolState> states = new ConcurrentHashMap<>(); // 按工具名保存互不影响的状态对象。
    private final LongSupplier clockMillis; // 保存单调毫秒时钟，避免系统时间回拨破坏窗口判断。

    public ToolCircuitBreaker() { // 创建生产使用的熔断器。
        this(() -> System.nanoTime() / 1_000_000L); // 使用单调纳秒时钟换算毫秒作为窗口来源。
    } // 结束生产熔断器构造方法。

    ToolCircuitBreaker(LongSupplier clockMillis) { // 接收包内测试可控的单调毫秒时钟。
        this.clockMillis = clockMillis; // 保存时钟供每次允许与记录判断读取。
    } // 结束可测试熔断器构造方法。

    public boolean allow(String toolName) { // 判断当前工具是否允许开始一次真实调用或唯一半开探测。
        return state(toolName).allow(clockMillis.getAsLong()); // 在该工具独立状态锁内完成窗口与半开判断。
    } // 结束工具调用许可判断方法。

    public void record(String toolName, String observation) { // 根据一次最终 Observation 更新对应工具的熔断状态。
        state(toolName).record(clockMillis.getAsLong(), isFailure(observation)); // 只把阶段约定的最终失败文本记入连续失败计数。
    } // 结束工具结果记录方法。

    private ToolState state(String toolName) { // 获取或原子创建指定工具的独立状态。
        return states.computeIfAbsent(toolName, ignored -> new ToolState()); // 首次访问时创建空状态，后续共享同一状态对象。
    } // 结束工具状态获取方法。

    private boolean isFailure(String observation) { // 判断最终 Observation 是否属于会触发熔断的真实工具失败。
        return observation != null && (observation.startsWith("工具执行超时：") || observation.startsWith("工具执行失败：")); // 只识别超时或执行失败，排除未知工具、限流与业务文本。
    } // 结束最终失败判断方法。

    private static final class ToolState { // 保存单个工具的连续失败、打开截止时间和半开探测标记。

        private int consecutiveFailures; // 记录尚未被成功结果清零的最终失败次数。
        private long openUntilMillis; // 保存熔断打开的单调截止时间，零表示当前未打开。
        private boolean halfOpenProbeRunning; // 标记到期后唯一允许的半开探测是否已经被占用。

        private synchronized boolean allow(long nowMillis) { // 原子判断并转换单个工具的许可状态。
            if (openUntilMillis == 0L) { // 未打开熔断时允许正常工具调用。
                return true; // 允许当前真实调用进入后续保护链。
            } // 结束关闭状态分支。
            if (nowMillis < openUntilMillis) { // 打开窗口尚未到期时必须直接拒绝。
                return false; // 阻止调用消耗重试、限流或真实工具资源。
            } // 结束打开窗口拒绝分支。
            if (halfOpenProbeRunning) { // 到期后已有另一条请求占用唯一半开探测。
                return false; // 拒绝并发探测，避免多个结果竞争恢复状态。
            } // 结束半开探测占用分支。
            halfOpenProbeRunning = true; // 原子取得唯一探测权，等待 record 根据结果关闭或重新打开。
            return true; // 允许本次调用作为半开探测进入真实工具链。
        } // 结束单工具许可状态转换方法。

        private synchronized void record(long nowMillis, boolean failed) { // 原子记录最终结果并更新连续失败和窗口状态。
            if (!failed) { // 正常 Observation 表示工具已恢复可用或持续可用。
                consecutiveFailures = 0; // 成功立即清空此前连续失败计数。
                openUntilMillis = 0L; // 成功探测关闭熔断并允许后续正常调用。
                halfOpenProbeRunning = false; // 释放半开标记，恢复关闭状态。
                return; // 成功结果无需继续进入失败逻辑。
            } // 结束成功结果恢复分支。
            if (halfOpenProbeRunning) { // 半开探测失败必须立即重新打开窗口而非累加历史状态。
                openUntilMillis = nowMillis + OPEN_DURATION_MILLIS; // 从当前失败时刻开始新的三十秒打开窗口。
                halfOpenProbeRunning = false; // 结束本次失败探测，后续到期前全部拒绝。
                consecutiveFailures = FAILURE_THRESHOLD; // 保持可诊断的阈值失败计数。
                return; // 半开失败已完成状态转换。
            } // 结束半开失败分支。
            consecutiveFailures++; // 关闭状态失败时增加连续最终失败计数。
            if (consecutiveFailures >= FAILURE_THRESHOLD) { // 达到第三次连续最终失败时打开熔断。
                openUntilMillis = nowMillis + OPEN_DURATION_MILLIS; // 记录从当前时刻起三十秒的拒绝截止时间。
            } // 结束失败阈值打开分支。
        } // 结束单工具最终结果记录方法。
    } // 结束单工具状态定义。
} // 结束工具熔断状态机定义。
