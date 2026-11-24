package cn.zhixing.travel.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Configuration
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpToolConfig {
    private static final Logger LOG = LoggerFactory.getLogger(McpToolConfig.class);
    private static final List<String> SERVICES =
            List.of("amap", "time", "weather", "flight", "hotel", "train", "biyingsearch");
    private static final String TUNIU_HOST = "openapi.tuniu.cn";
    private static final Map<String, Set<String>> TUNIU_QUERIES =
            Map.of(
                    "train", Set.of("searchLowestPriceTrain", "queryTrainDetail"),
                    "flight", Set.of("searchLowestPriceFlight", "multiCabinDetails"),
                    "hotel", Set.of("tuniuHotelSearch", "tuniuHotelDetail"));
    private static final Set<String> MAP_QUERIES =
            Set.of(
                    "maps_geo",
                    "maps_regeocode",
                    "maps_ip_location",
                    "maps_weather",
                    "maps_bicycling",
                    "maps_direction_walking",
                    "maps_direction_driving",
                    "maps_direction_transit_integrated",
                    "maps_distance",
                    "maps_text_search",
                    "maps_around_search",
                    "maps_search_detail");

    @Bean(destroyMethod = "close")
    ToolClients travelToolClients(Environment env) {
        Map<String, McpClient> clients = new LinkedHashMap<>();
        Map<McpClient, Set<String>> queryOnly = new LinkedHashMap<>();
        String modelScopeToken = env.getProperty("mcp.api-key", "");
        String tuniuKey = env.getProperty("mcp.tuniu-key", "").strip();
        for (String name : SERVICES) {
            String url = env.getProperty("mcp." + name + "-url", "").strip();
            if (name.equals("amap") && url.isEmpty()) {
                String mapKey = env.getProperty("mcp.amap-key", "").strip();
                if (!mapKey.isEmpty()) {
                    url =
                            "https://mcp.amap.com/mcp?key="
                                    + URLEncoder.encode(mapKey, StandardCharsets.UTF_8);
                }
            }
            if (url.isEmpty() && !tuniuKey.isEmpty() && TUNIU_QUERIES.containsKey(name)) {
                url = "https://" + TUNIU_HOST + "/mcp/" + name;
            }
            if (url.isEmpty()) continue;
            try {
                URI endpoint = URI.create(url);
                if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
                    LOG.warn("跳过非 HTTPS 旅行工具：{}", name);
                    continue;
                }
                var transport = StreamableHttpMcpTransport.builder().url(endpoint.toString());
                boolean tuniu = TUNIU_HOST.equalsIgnoreCase(endpoint.getHost());
                // 按主机发送对应的凭证，手动覆盖 URL 也遵循同样的限制。
                if (tuniu) {
                    if (tuniuKey.isBlank() || !TUNIU_QUERIES.containsKey(name)) {
                        LOG.warn("途牛查询工具未配置：{}", name);
                        continue;
                    }
                    transport.customHeaders(() -> Map.of("apiKey", tuniuKey));
                } else if ("mcp.api-inference.modelscope.net".equalsIgnoreCase(endpoint.getHost())
                        && !modelScopeToken.isBlank()) {
                    transport.customHeaders(
                            () ->
                                    Map.of(
                                            "Authorization",
                                            "Bearer " + modelScopeToken,
                                            "x-api-key",
                                            modelScopeToken));
                }
                var client =
                        new DefaultMcpClient.Builder()
                                .key(name)
                                .transport(transport.build())
                                .build();
                clients.put(name, client);
                if (tuniu) queryOnly.put(client, TUNIU_QUERIES.get(name));
                else if (name.equals("amap")) queryOnly.put(client, MAP_QUERIES);
            } catch (RuntimeException error) {
                // 某个工具掉线时，保留其他可用工具和基本规划功能。
                LOG.warn("旅行工具未能初始化：{} ({})", name, error.getClass().getSimpleName());
            }
        }
        return new ToolClients(clients, queryOnly);
    }

    @Bean(name = {"mcpToolProvider", "toolsNormal"})
    McpToolProvider normal(ToolClients tools) {
        return tools.provider(SERVICES.toArray(String[]::new));
    }

    @Bean("toolsRequirement")
    McpToolProvider requirement(ToolClients tools) {
        return tools.provider("time", "amap", "weather");
    }

    @Bean("toolsAccommodation")
    McpToolProvider accommodation(ToolClients tools) {
        return tools.provider("time", "hotel");
    }

    @Bean("toolsFood")
    McpToolProvider food(ToolClients tools) {
        return tools.provider("time", "amap", "weather", "biyingsearch");
    }

    @Bean(name = {"toolsDriving", "toolsTransportPlanner"})
    McpToolProvider driving(ToolClients tools) {
        return tools.provider("time", "amap", "weather");
    }

    @Bean("toolsFlight")
    McpToolProvider flight(ToolClients tools) {
        return tools.provider("time", "amap", "weather", "flight");
    }

    @Bean("toolsTrain")
    McpToolProvider train(ToolClients tools) {
        return tools.provider("time", "amap", "weather", "train");
    }

    record ToolClients(Map<String, McpClient> clients, Map<McpClient, Set<String>> queryOnly)
            implements AutoCloseable {
        McpToolProvider provider(String... roles) {
            List<McpClient> selected =
                    Arrays.stream(roles)
                            .map(clients::get)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList();
            return McpToolProvider.builder()
                    .mcpClients(selected)
                    .filter(
                            (client, tool) -> {
                                Set<String> allowed = queryOnly.get(client);
                                return allowed == null || allowed.contains(tool.name());
                            })
                    .failIfOneServerFails(false)
                    .build();
        }

        @Override
        public void close() {
            clients.forEach(
                    (name, client) -> {
                        try {
                            client.close();
                        } catch (Exception error) {
                            LOG.warn("旅行工具关闭失败：{}", name);
                        }
                    });
        }
    }
}
