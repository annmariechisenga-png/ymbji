package com.localgov.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localgov.web.testutil.TestAuthSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Tag("integration")
@Testcontainers
@ActiveProfiles("rls-test")
class SecurityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_security_it")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("pii.encryption.key", () -> "dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=");
        registry.add("pii.hmac.key", () -> "dGVzdC1waWktaG1hYy1rZXktMzJieXRlcy1oZXJlMTI=");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private TestAuthSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder.seedStandardUsers();
    }

    @Test
    void loginReturnsJwtToken() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginPayload("admin", "Admin@123"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.dashboardIdentity.positionId").value("DIRECTOR_HR_ADMIN"))
                .andExpect(jsonPath("$.dashboardIdentity.authorityType").value("Town Council"))
                .andExpect(jsonPath("$.dashboardIdentity.source").value("user-account"))
                .andExpect(jsonPath("$.mfaRequired").value(false));
    }

    @Test
    void authMeReturnsResolvedDashboardIdentity() throws Exception {
        String token = tokenFor("finance", "FINANCE");

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("finance"))
                .andExpect(jsonPath("$.dashboardIdentity.positionId").value("DIRECTOR_FINANCE"))
                .andExpect(jsonPath("$.dashboardIdentity.authorityType").value("Town Council"));
    }

    @Test
    void employeeEndpointWithoutTokenIsForbidden() throws Exception {
        mockMvc.perform(get("/employees"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeeEndpointWithEmployeeRoleIsForbidden() throws Exception {
        String token = tokenFor("employee", "EMPLOYEE");

        mockMvc.perform(get("/employees")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeEndpointWithHrRoleIsAllowed() throws Exception {
        String token = tokenFor("hr", "HR");

        mockMvc.perform(get("/employees")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void termsDocumentWithoutTokenIsAccessible() throws Exception {
        mockMvc.perform(get("/documents/terms-and-conditions"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")));
    }

    @Test
    void homePageSetsCsrfCookie() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/chilanga/login.html"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void privacyComplianceEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/privacy/compliance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gdprSupported").value(true))
                .andExpect(jsonPath("$.ccpaSupported").value(true));
    }

    @Test
    void auditLogsEndpointIsRestrictedToAdminAndHr() throws Exception {
        String adminToken = tokenFor("admin", "ADMIN");

        mockMvc.perform(get("/security/audit-logs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        String employeeToken = tokenFor("employee", "EMPLOYEE");
        mockMvc.perform(get("/security/audit-logs")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedUserCanSubmitPrivacyRequest() throws Exception {
        String employeeToken = tokenFor("employee", "EMPLOYEE");

        mockMvc.perform(post("/privacy/data-requests")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestType": "EXPORT",
                                  "details": "Please provide my personal payroll records."
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestType").value("EXPORT"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void termsDocumentIsAccessibleToEmployeeViewDownloadAndPrint() throws Exception {
        String token = tokenFor("employee", "EMPLOYEE");

        mockMvc.perform(get("/documents/terms-and-conditions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")));

        mockMvc.perform(get("/documents/terms-and-conditions/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));

        mockMvc.perform(get("/documents/terms-and-conditions/print")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")));
    }

    private String tokenFor(String username, String role) {
        UserDetails userDetails = User.builder()
                .username(username)
                .password("ignored")
                .roles(role)
                .build();
        return jwtService.generateToken(userDetails);
    }

    private record LoginPayload(String username, String password) {
    }
}
