package com.jaycong.dodo.tool; // 将工具目录与具体工具实现放在同一边界包中。

import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存 Agent 当前允许使用的全部工具，并提供名称到执行回调的统一映射。
 * 模型只读取 callbacks 声明能力，真正执行始终经过 execute，因此错误可以统一转换为 Observation。
 */
public class AgentToolRegistry { // 定义与 Spring AI 自动工具执行解耦的人工工具执行边界。

    private final List<ToolCallback> callbacks; // 按配置顺序保存不可变回调列表，供模型声明工具能力。
    private final Map<String, ToolCallback> callbacksByName; // 按唯一工具名保存不可变索引，供 ReAct 循环执行调用。

    public AgentToolRegistry(List<ToolCallback> callbacks) { // 接收已经由 Spring AI 从工具对象转换出的回调列表。
        this.callbacks = List.copyOf(callbacks); // 创建防御性不可变副本，避免外部在运行时改变可用工具集合。
        Map<String, ToolCallback> index = new LinkedHashMap<>(); // 创建保留注册顺序的临时名称索引。
        for (ToolCallback callback : this.callbacks) { // 遍历同一份不可变回调，保证声明与执行不会使用不同来源。
            String name = callback.getToolDefinition().name(); // 从 Spring AI 官方工具定义读取模型看到的名称。
            if (index.putIfAbsent(name, callback) != null) { // 原子式检查同一配置中是否出现重复工具名。
                throw new IllegalArgumentException("Duplicate tool name: " + name); // 尽早拒绝歧义配置，避免运行时调用错误工具。
            } // 结束重复名称保护分支。
        } // 结束工具回调索引构建循环。
        this.callbacksByName = Map.copyOf(index); // 冻结名称索引，确保一次运行期间工具解析规则稳定。
    } // 结束工具注册表构造方法。

    public ToolCallback[] callbacks() { // 导出模型选项所需的工具回调数组。
        return callbacks.toArray(ToolCallback[]::new); // 每次返回新数组，防止调用方修改注册表内部顺序。
    } // 结束工具回调导出方法。

    /**
     * 根据模型返回的工具名称和原始 JSON 参数执行本地工具。
     * 未知工具、工具异常和空结果都属于可恢复错误，会变成 Observation 交给下一轮模型决策。
     */
    public String execute(String toolName, String arguments) { // 定义手写 ReAct 循环唯一允许使用的工具执行入口。
        ToolCallback callback = callbacksByName.get(toolName); // 使用模型返回的名称查找对应 Spring AI 回调。
        if (callback == null) { // 未找到名称通常表示模型幻觉或工具配置发生变化。
            return "工具执行失败：未找到工具 " + toolName; // 返回稳定 Observation，让模型能够解释或改正。
        } // 结束未知工具保护分支。
        try { // 隔离参数反序列化、业务实现和第三方回调可能抛出的所有运行时失败。
            String result = callback.call(arguments); // 将模型原始 JSON 参数不经改写地交给目标工具。
            if (result == null || result.isBlank()) { // 工具无返回值时模型无法获得有效观察，因此需要显式标记。
                return "工具执行失败：工具未返回结果"; // 把 null 和空白统一转换为可理解的失败 Observation。
            } // 结束空工具结果保护分支。
            return result; // 正常结果保持原样返回，供 SSE 展示和 ToolResponseMessage 回填。
        } catch (Exception error) { // 捕获单个工具执行异常，避免它升级成整轮 Agent 失败。
            String message = error.getMessage(); // 读取最接近工具失败原因的异常说明。
            if (message == null || message.isBlank()) { // 某些运行时异常可能没有可读消息。
                message = error.getClass().getSimpleName(); // 回退到异常类型，确保 Observation 始终有内容。
            } // 结束空异常消息回退分支。
            return "工具执行失败：" + message; // 返回带统一前缀的失败 Observation，允许模型继续推理。
        } // 结束工具执行异常隔离边界。
    } // 结束按名称执行工具的方法。
} // 结束 Agent 工具注册表定义。
