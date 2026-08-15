package com.jaycong.llm.function;


import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @author pyc
 * @since 2026-08-15 14:58
 */
@Slf4j
public class WeatherTool {

    @Tool(name = "getWeather", description = "根据用户输入的城市获取该城市的当前天气", returnDirect = true)
    public String getWeather(@ToolParam(description = "城市名称 例如上海、杭州") String city) {
        log.info("getWeather，city=" + city);

        if ("上海".equals(city)) {
            return "晴天";
        } else if ("纽约".equals(city)) {
            return "多云";
        } else if ("北京".equals(city)) {
            return "大晴天☀️";
        } else {
            return "大雨";
        }
    }
}
