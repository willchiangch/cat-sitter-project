package com.catsitter.api.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Utility test to export the OpenAPI specification to a static file.
 * This is used by the frontend to sync API contracts and generate the SDK.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class OpenApiExportTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportOpenApiJson() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        
        // Export to the backend root directory so it's easily accessible by the frontend
        Path outputPath = Paths.get("openapi.json");
        Files.writeString(outputPath, content);
        
        System.out.println("OpenAPI specification exported to: " + outputPath.toAbsolutePath());
    }
}
