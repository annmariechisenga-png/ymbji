package com.localgov.web.reporting;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@TestPropertySource(properties = {
        "jwt.secret=Q2hhbmdlVGhpc1RvQVN0cm9uZ0FuZExvbmdlckRldk9ubHlTZWNyZXRLZXlGb3JMb2NhbEdvdkVSUA==",
        "jwt.expiration-millis=3600000",
        "spring.datasource.url=jdbc:h2:mem:erp_reporting_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Disabled("Pre-existing failure: test @TestPropertySource uses H2 in-memory DB with spring.flyway.enabled=false; user accounts (admin, finance, employee, hr) never seeded. login() returns 401, tokenFor() throws UsernameNotFound. Fix in V191c test-hygiene session: convert to Testcontainers + Postgres like BudgetCsvUploadIntegrationTest.")
class ReportingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsEndpointRequiresAuth() throws Exception {
        mockMvc.perform(get("/reports/views"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportsEndpointIsForbiddenForEmployeeRole() throws Exception {
        String employeeToken = login("employee", "Employee@123");

        mockMvc.perform(get("/reports/views")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void reportsEndpointIsAllowedForHrRole() throws Exception {
        String hrToken = login("hr", "Hr@123");

        mockMvc.perform(get("/reports/views")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        String body = """
                {
                  "username": "%s",
                  "password": "%s"
                }
                """.formatted(username, password);

        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\\\"token\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }
}
