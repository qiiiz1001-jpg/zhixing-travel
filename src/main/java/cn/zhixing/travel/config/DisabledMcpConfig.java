package cn.zhixing.travel.config;

import dev.langchain4j.mcp.McpToolProvider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledMcpConfig {
    @Bean(
            name = {
                "mcpToolProvider",
                "toolsNormal",
                "toolsRequirement",
                "toolsAccommodation",
                "toolsFood",
                "toolsDriving",
                "toolsFlight",
                "toolsTrain",
                "toolsTransportPlanner"
            })
    public McpToolProvider noExternalTools() {
        return McpToolProvider.builder().mcpClients(List.of()).build();
    }
}
