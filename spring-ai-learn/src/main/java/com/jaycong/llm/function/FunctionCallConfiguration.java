package com.jaycong.llm.function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

@Configuration
public class FunctionCallConfiguration {

    @Bean
    @Description("根据用户输入的城市获取该城市的当前天气")
    public Function<WeatherService.Request, WeatherService.Response> getWeather(WeatherService weatherService) {
        return weatherService::geWeather;
    }
}
