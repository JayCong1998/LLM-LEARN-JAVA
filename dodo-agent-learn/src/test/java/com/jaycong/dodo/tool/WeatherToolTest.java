package com.jaycong.dodo.tool; // 将天气工具测试放在对应工具包中。

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherToolTest { // 定义确定性天气查询规则的单元测试。

    private final WeatherTool tool = new WeatherTool(); // 创建不依赖网络和 Spring 容器的真实天气工具。

    @Test
    void returnsFixedWeatherForSupportedCities() { // 验证三个教学城市始终返回可复现结果。
        assertThat(tool.getWeather("北京")).isEqualTo("北京：晴，25℃"); // 固定北京天气结果。
        assertThat(tool.getWeather(" 上海 ")).isEqualTo("上海：多云，27℃"); // 验证查询前会清理首尾空格。
        assertThat(tool.getWeather("深圳")).isEqualTo("深圳：阵雨，30℃"); // 固定深圳天气结果。
    } // 结束已支持城市测试。

    @Test
    void returnsStableObservationForUnknownCity() { // 验证未知或空城市不会抛出异常击穿 Agent 循环。
        assertThat(tool.getWeather("杭州")).isEqualTo("暂无杭州的天气数据"); // 未配置城市返回明确 Observation。
        assertThat(tool.getWeather("  ")).isEqualTo("城市名称不能为空"); // 空白参数返回模型可理解的校验信息。
    } // 结束未知城市边界测试。
} // 结束天气工具测试类。
