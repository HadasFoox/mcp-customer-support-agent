package com.cheq.support;

import com.cheq.support.agent.SupportAnalysisTool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

/**
 * Entry point for the CHEQ support-ticket analysis MCP server.
 *
 * <p>Runs in strict non-web mode ({@link WebApplicationType#NONE}) because the server
 * speaks JSON-RPC over STDIO. stdout is reserved exclusively for protocol frames — all
 * logging goes to file only (see {@code logback-spring.xml}).
 */
@SpringBootApplication
public class McpSupportServerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(McpSupportServerApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Bean
    public MethodToolCallbackProvider toolCallbackProvider(SupportAnalysisTool tool) {
        return MethodToolCallbackProvider.builder().toolObjects(tool).build();
    }
}
