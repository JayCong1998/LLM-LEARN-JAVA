package com.jaycong.dodo.react;


import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * @author pyc
 * @since 2026-08-21 16:36
 */
@Slf4j
@Component
public class WebSearchMcpCreator implements InitializingBean {

    /**
     * Tavily 搜索引擎 API Key
     */
    @Value("${tavily.api-key}")
    private String tavilyApiKey;

    /**
     * Tavily MCP URL
     */
    @Value("${tavily.mcp-url}")
    private String tavilyMcpUrl;

    private List<ToolCallback> webSearchToolCallbacks;

    public String getTavilyApiKey() {
        return tavilyApiKey;
    }

    public void setTavilyApiKey(String tavilyApiKey) {
        this.tavilyApiKey = tavilyApiKey;
    }

    public String getTavilyMcpUrl() {
        return tavilyMcpUrl;
    }

    public void setTavilyMcpUrl(String tavilyMcpUrl) {
        this.tavilyMcpUrl = tavilyMcpUrl;
    }

    public List<ToolCallback> getWebSearchToolCallbacks() {
        return webSearchToolCallbacks;
    }

    public void setWebSearchToolCallbacks(List<ToolCallback> webSearchToolCallbacks) {
        this.webSearchToolCallbacks = webSearchToolCallbacks;
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        log.info("初始化网页搜索工具回调...");

        // tavily 搜索引擎 - Keyless 模式
        // 必须设置 X-Tavily-Access-Mode: keyless header
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .header("X-Tavily-Access-Mode", "keyless");

        // 如果有 API key，使用 Bearer token 认证（API key 优先于 keyless）
        if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + tavilyApiKey);
        }

        HttpClientStreamableHttpTransport tavTransport = HttpClientStreamableHttpTransport.builder(tavilyMcpUrl)
                .requestBuilder(requestBuilder).build();
        McpSyncClient tavilyMcp = McpClient.sync(tavTransport)
                .requestTimeout(Duration.ofSeconds(300))
                .build();
        tavilyMcp.initialize();

        List<McpSyncClient> mcpClients = List.of(tavilyMcp);
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder().mcpClients(mcpClients).build();

        webSearchToolCallbacks = Arrays.asList(provider.getToolCallbacks());
        log.info("网页搜索工具回调初始化完成，工具数量: {}", webSearchToolCallbacks.size());
    }
}
