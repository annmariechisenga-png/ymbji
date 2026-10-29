package com.localgov.web.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class FederationUIPageTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_federation_ui")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("pii.encryption.key",
            () -> "dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=");
        registry.add("pii.hmac.key",
            () -> "dGVzdC1waWktaG1hYy1rZXktMzJieXRlcy1oZXJlMTI=");
    }

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("platform/login.html is served with MFA field")
    void loginPageServed() throws Exception {
        String body = mockMvc.perform(get("/platform/login.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("YMBJI Platform");
        assertThat(body).contains("id=\"username\"");
        assertThat(body).contains("id=\"password\"");
        assertThat(body).contains("id=\"mfaCode\"");
        assertThat(body).contains("mfaRequired");
    }

    @Test
    @DisplayName("platform/preview.html is served with upload UI")
    void previewPageServed() throws Exception {
        String body = mockMvc.perform(get("/platform/preview.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("YMBJI");
        assertThat(body).contains("Federation Data Layer");
        assertThat(body).contains("id=\"uploadType\"");
        assertThat(body).contains("id=\"fileInput\"");
        assertThat(body).contains("id=\"previewBtn\"");
        assertThat(body).contains("id=\"confirmBtn\"");
        assertThat(body).contains("id=\"ackWarnings\"");
    }

    @Test
    @DisplayName("preview.html targets the real federation API endpoints")
    void previewPageTargetsRealEndpoints() throws Exception {
        String body = mockMvc.perform(get("/platform/preview.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("/federation/rates/notch-values");
        assertThat(body).contains("/federation/rates/allowance-rates");
        assertThat(body).contains("/federation/hardship-classifications");
        assertThat(body).contains("/confirm");
        assertThat(body).contains("erp_token");
    }
}
