package com.jaycong.llm.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class WeatherService {

    public Response geWeather(Request request) {
        log.info("geWeather，city=" + request.city);

        if ("上海".equals(request.city)) {
            return new Response("晴天");
        } else if ("纽约".equals(request.city)) {
            return new Response("多云");
        } else if ("北京".equals(request.city)) {
            return new Response("大晴天☀️");
        } else {
            return new Response("大雨");
        }
    }

    public record Request(@JsonProperty(required = true, value = "city")
                          @JsonPropertyDescription("城市，比如 纽约") String city) {
    }

    public record Response(String result) {
    }
}
