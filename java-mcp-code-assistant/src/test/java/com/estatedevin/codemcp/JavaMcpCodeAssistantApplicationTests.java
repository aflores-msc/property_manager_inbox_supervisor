package com.estatedevin.codemcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.mcp.server.stdio=false"
})
class JavaMcpCodeAssistantApplicationTests {

    @Test
    void contextLoads() {
    }
}
