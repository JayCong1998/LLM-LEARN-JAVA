package com.jaycong.dodo.tool; // 将会话维度的工具限流状态机放在工具执行链所在包中。

import java.util.ArrayDeque; // 引入双端队列保存按时间顺序排列的许可时间点。
import java.util.concurrent.ConcurrentHashMap; // 引入并发映射隔离不同会话和工具的限流桶。
import java.util.concurrent.ConcurrentMap; // 引入并发映射接口表达并发访问边界。
import java.util.function.LongSupplier; // 引入可替换的单调时钟以支持确定性测试。

/**
 * 使用滑动时间窗口限制每个会话对每个工具的真实执行次数。
 * 每个桶仅在自身锁内清理过期时间点和分配许可，避免并发请求突破十次上限。
 */
public class ToolRateLimiter { // 定义不依赖 Web 或 Spring 的进程内工具限流器。

    private static final int MAX_CALLS_PER_WINDOW = 10; // 定义单个会话和工具组合在窗口内允许的最大真实调用次数。
    private static final long WINDOW_MILLIS = 60_000L; // 定义一分钟滑动窗口的毫秒长度。
    private final ConcurrentMap<RateLimitKey, CallWindow> windows = new ConcurrentHashMap<>(); // 按会话和工具组合保存相互隔离的调用窗口。
    private final LongSupplier clockMillis; // 保存单调毫秒时钟，避免系统时间变化影响窗口判定。

    public ToolRateLimiter() { // 创建生产环境使用的工具限流器。
        this(() -> System.nanoTime() / 1_000_000L); // 使用单调纳秒时钟换算毫秒以稳定计算经过时间。
    } // 结束生产限流器构造方法。

    ToolRateLimiter(LongSupplier clockMillis) { // 接收包内测试可控的单调毫秒时钟。
        this.clockMillis = clockMillis; // 保存时钟供每次申请许可时读取。
    } // 结束可测试限流器构造方法。

    public boolean tryAcquire(String conversationId, String toolName) { // 原子申请指定会话对指定工具的一次真实调用许可。
        RateLimitKey key = new RateLimitKey(conversationId, toolName); // 使用两个稳定标识构造彼此隔离的限流桶键。
        CallWindow window = windows.computeIfAbsent(key, ignored -> new CallWindow()); // 首次访问时原子创建空窗口，后续请求共享该窗口。
        return window.tryAcquire(clockMillis.getAsLong()); // 在单桶锁内清理过期记录并决定是否分配许可。
    } // 结束工具调用许可申请方法。

    private record RateLimitKey(String conversationId, String toolName) { // 定义作为并发映射键的不可变会话和工具组合。
    } // 结束限流桶键定义。

    private static final class CallWindow { // 保存单个会话和工具组合在当前窗口内的调用时间点。

        private final ArrayDeque<Long> callTimes = new ArrayDeque<>(); // 按从早到晚的顺序保存尚未过期的真实调用时间点。

        private synchronized boolean tryAcquire(long nowMillis) { // 原子清理当前窗口并在未满时登记一次许可。
            while (!callTimes.isEmpty() && nowMillis - callTimes.peekFirst() >= WINDOW_MILLIS) { // 移除恰好满一分钟或更早的时间点，使窗口严格表示最近一分钟。
                callTimes.removeFirst(); // 删除队首最早的过期调用记录。
            } // 结束过期调用记录清理循环。
            if (callTimes.size() >= MAX_CALLS_PER_WINDOW) { // 窗口内已经存在十次真实调用时必须拒绝本次请求。
                return false; // 不登记拒绝请求，避免客户端重试进一步延长限流窗口。
            } // 结束窗口已满分支。
            callTimes.addLast(nowMillis); // 为获得许可的真实调用登记当前时间点。
            return true; // 报告后续执行链可以触达重试和实际工具。
        } // 结束单桶许可申请方法。
    } // 结束单会话单工具调用窗口定义。
} // 结束工具限流器定义。
