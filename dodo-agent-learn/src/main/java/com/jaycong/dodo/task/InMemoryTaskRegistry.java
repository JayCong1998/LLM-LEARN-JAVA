package com.jaycong.dodo.task; // 将任务注册表放在独立任务包中，集中管理运行状态和取消资源。

import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 保存当前进程内正在运行的 Agent 任务。
 * 会话编号既是并发互斥键，也是停止接口定位模型订阅的索引。
 * 该实现只适用于单进程学习场景；多实例部署需要替换为分布式任务协调机制。
 */
@Component
public class InMemoryTaskRegistry { // 定义任务注册、订阅绑定、取消和完成清理的统一入口。

    private final ConcurrentMap<String, TaskEntry> tasks = new ConcurrentHashMap<>(); // 按会话编号保存运行任务，并支持无全局锁并发访问。

    /**
     * 尝试为会话注册一个新任务。
     * putIfAbsent 把“检查是否存在”和“写入新任务”合并成原子操作，避免并发请求同时启动。
     *
     * @param conversationId 会话唯一编号
     * @param onCancel       主动取消后需要通知下游输出流的回调
     * @return true 表示注册成功，false 表示该会话已经有任务运行
     */
    public boolean register(String conversationId, Runnable onCancel) { // 接收会话键和取消回调，并返回原子注册结果。
        return tasks.putIfAbsent(conversationId, new TaskEntry(onCancel)) == null; // 只有原位置为空时才插入任务并报告成功。
    } // 结束任务注册方法。

    public boolean hasRunningTask(String conversationId) { // 查询指定会话当前是否仍保存在运行任务集合中。
        return tasks.containsKey(conversationId); // 使用并发 Map 的线程安全查询返回实时存在状态。
    } // 结束运行状态查询方法。

    /**
     * 把模型订阅句柄绑定到已经注册的会话任务。
     * 注册和取得 Disposable 分属两个步骤，因此这里必须处理任务已经被并发取消的情况。
     *
     * @param conversationId 目标会话编号
     * @param subscription   订阅模型流后得到的可释放句柄
     */
    public void attach(String conversationId, Disposable subscription) { // 接收会话键和稍后产生的模型订阅句柄。
        TaskEntry entry = tasks.get(conversationId); // 读取注册阶段创建的任务条目。
        if (entry == null) { // 如果条目已经被取消或完成线程移除，说明该订阅来得太晚。
            subscription.dispose(); // 立即释放迟到的订阅，防止没有注册表入口的模型请求继续消耗资源。
            return; // 终止绑定流程，因为不存在可以接管该订阅的任务条目。
        } // 结束任务条目缺失分支。
        entry.attach(subscription); // 把订阅交给条目内部的同步方法，继续处理更细粒度的取消竞争。
    } // 结束订阅绑定方法。

    /**
     * 主动取消指定会话的任务。
     * 先从 Map 移除条目，可确保后续停止请求不会重复取消同一个任务。
     *
     * @param conversationId 需要停止的会话编号
     * @return true 表示找到了任务并发起取消，false 表示任务不存在或已经结束
     */
    public boolean cancel(String conversationId) { // 根据会话编号发起一次幂等取消尝试。
        TaskEntry entry = tasks.remove(conversationId); // 原子移除任务，使当前线程成为该任务唯一的外部取消者。
        if (entry == null) { // 如果没有取到条目，说明没有可取消的运行任务。
            return false; // 向停止接口报告本次调用没有实际取消任务。
        } // 结束任务不存在分支。
        entry.cancel(); // 进入条目的同步取消逻辑，先通知输出流取得终止权，再释放上游订阅。
        return true; // 向调用方报告已经找到并处理对应任务。
    } // 结束主动取消方法。

    /**
     * 标记指定任务已经正常或异常终止，并从运行集合移除它。
     * 完成清理不执行 onCancel，因为上层已经发送了正确的完成或错误事件。
     *
     * @param conversationId 已经结束的会话编号
     */
    public void complete(String conversationId) { // 根据会话编号执行无取消副作用的完成清理。
        TaskEntry entry = tasks.remove(conversationId); // 移除结束任务，使同一会话可以在之后启动新一轮请求。
        if (entry != null) { // 只有当前线程成功取到条目时才需要更新条目内部状态。
            entry.complete(); // 把条目标记为关闭，阻止竞争中的迟到订阅被保存。
        } // 结束条目存在分支；条目缺失表示其他终止路径已经完成清理。
    } // 结束正常完成清理方法。

    /**
     * 保存单个任务中必须一致变化的组合状态。
     * 外层 ConcurrentMap 只保护条目引用，条目内部仍需同步保护 closed 与 subscription 的关系。
     */
    private static final class TaskEntry { // 定义不依赖外部注册表实例的私有任务状态对象。
        private final Runnable onCancel; // 保存主动取消回调，用于通知 Agent 输出错误和完成事件。
        private Disposable subscription; // 保存模型流订阅，主动停止时通过它取消上游请求。
        private boolean closed; // 记录任务是否已经取消或完成，防止迟到操作重新激活任务。

        private TaskEntry(Runnable onCancel) { // 创建条目时先保存回调，模型订阅会在稍后的 attach 阶段补充。
            this.onCancel = onCancel; // 保存不可变取消回调，确保取消路径始终可以通知下游。
        } // 结束任务条目构造方法。

        /*
         * 注册任务与取得模型 Disposable 不是同一个原子步骤。
         * 如果取消先发生，attach 会看到 closed=true，并立即 dispose 迟到的订阅，避免模型继续运行。
         */
        private synchronized void attach(Disposable newSubscription) { // 锁定当前条目，原子检查关闭状态并绑定订阅。
            if (closed) { // 如果取消或完成已经先获得锁并关闭任务，就不能再保存这个订阅。
                newSubscription.dispose(); // 立即释放竞争中迟到的订阅，切断上游模型流。
                return; // 终止绑定，保持关闭任务不可逆的状态约束。
            } // 结束已关闭分支。
            subscription = newSubscription; // 在任务仍打开时保存句柄，供之后的停止请求释放。
        } // 释放条目锁并结束订阅绑定方法。

        private synchronized void cancel() { // 锁定当前条目，使关闭、通知下游和释放订阅按固定顺序执行。
            if (closed) { // 如果其他终止路径已经关闭任务，就不重复释放资源或执行回调。
                return; // 直接返回，从而让条目级取消操作具备幂等性。
            } // 结束重复关闭保护分支。
            closed = true; // 先设置不可逆关闭标记，让并发 attach 能识别取消已经发生。
            onCancel.run(); // 先让 Agent 的取消路径取得终止闸门，避免 dispose 引发的中断异常抢先输出成普通错误。
            if (subscription != null) { // 模型订阅可能尚未产生，因此释放前必须检查是否已经绑定。
                subscription.dispose(); // 通知下游取消状态后再中断上游工作，停止模型生成并释放资源。
            } // 结束已绑定订阅的资源释放分支。
        } // 释放条目锁并结束主动取消方法。

        private synchronized void complete() { // 锁定条目并记录正常或异常终止，防止迟到订阅被保存。
            closed = true; // 设置关闭标记；上游已经终止，因此无需 dispose，也不能执行取消回调。
        } // 释放条目锁并结束完成标记方法。
    } // 结束单任务状态类型定义。
} // 结束内存任务注册表定义。
