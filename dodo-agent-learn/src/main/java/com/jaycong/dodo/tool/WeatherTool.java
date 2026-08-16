package com.jaycong.dodo.tool; // 将可供 Agent 使用的本地工具集中放在 tool 包中。

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 提供完全确定性的本地天气查询，专门用于学习工具调用链路。
 * 该工具不访问外部服务，因此测试、课堂演示和离线运行都能得到相同结果。
 */
@Component
public class WeatherTool { // 定义可由 Spring 管理并转换为 ToolCallback 的天气能力。

    private static final Map<String, String> WEATHER_BY_CITY = Map.of( // 保存三个教学城市的不可变天气快照。
            "北京", "北京：晴，25℃", // 配置北京的固定天气结果。
            "上海", "上海：多云，27℃", // 配置上海的固定天气结果。
            "深圳", "深圳：阵雨，30℃"); // 配置深圳的固定天气结果并结束 Map 构造。

    @Tool(name = "weather", description = "查询北京、上海或深圳的固定教学天气数据")
    public String getWeather( // 暴露给模型一个名称稳定、无外部副作用的天气查询方法。
            @ToolParam(description = "城市名称，例如北京、上海、深圳")
            String city) { // 接收模型从用户问题中提取出的城市名称。
        if (city == null || city.isBlank()) { // 在读取 Map 前处理模型遗漏参数或只传空白的情况。
            return "城市名称不能为空"; // 返回可作为 Observation 回填给模型的校验结果。
        } // 结束空城市保护分支。
        String normalizedCity = city.trim(); // 清理首尾空格，避免语义相同的输入查询失败。
        return WEATHER_BY_CITY.getOrDefault( // 优先返回固定数据，未知城市则生成稳定说明。
                normalizedCity, // 使用规范化城市名查询不可变天气表。
                "暂无" + normalizedCity + "的天气数据"); // 未配置城市不抛异常，而是返回模型可理解的 Observation。
    } // 结束确定性天气查询方法。
} // 结束天气工具定义。
