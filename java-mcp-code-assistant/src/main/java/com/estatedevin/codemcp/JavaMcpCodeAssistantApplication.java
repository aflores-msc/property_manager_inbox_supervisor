package com.estatedevin.codemcp;

import com.estatedevin.codemcp.ollama.OllamaCodeAssistantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OllamaCodeAssistantProperties.class)
public class JavaMcpCodeAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaMcpCodeAssistantApplication.class, args);
    }
}
