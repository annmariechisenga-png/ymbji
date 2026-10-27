package com.localgov.web.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase B.1 acceptance test — superadmin role gate.
 *
 * <p>Proves three things:
 * <ul>
 *   <li>Superadmin (PLATFORM tenant + is_superadmin) can call /federation/ping → 200</li>
 *   <li>Council admin (non-PLATFORM tenant, is_superadmin=false) → 403</li>
 *   <li>Anonymous → 401</li>
 * </ul>
 *
 * <p>The superadmin account is seeded by V157 (admin.superadmin).
 * The council admin is seeded here to ensure a fresh, isolated user
 * exists independent of test order.
 */
@SpringBootTest(classes = com.localgov.web.LocalGovErpApplication.class)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("rls-test")
class FederationAdminAuthorizationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("localgov_federation_auth")
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
            () -> "dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=".replace("encryption", "hmac"));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext applicationContext;

    private static final String CHILANGA = "ZM-09-01";

    @BeforeEach
    void seed() {
        // Clean any existing council admin (superadmin is seeded by V157 and stable)
        jdbc.execute("DELETE FROM erp_user_account WHERE username = 'chilanga.admin'");
        jdbc.execute("DELETE FROM employees WHERE employee_id = 'EMP-COUNCIL-ADM'");

        // Seed a synthetic council employee + account
        jdbc.update("""
            INSERT INTO employees (
                employee_id, authority_code, first_name, last_name,
                salary_scale, current_notch, date_of_first_appointment,
                date_reported, employment_status, bargaining_unit, union_code
            ) VALUES (
                'EMP-COUNCIL-ADM', ?, 'Council', 'Admin',
                'LGSS08', 1, DATE '2020-01-01', DATE '2020-01-01',
                'ACTIVE', 'UNIONIZED_ZULAWU', 'ZULAWU'
            )
            """, CHILANGA);

        jdbc.update("""
            INSERT INTO erp_user_account (
                username, password_hash, employee_id, roles_csv,
                dashboard_position_id, authority_code, authority_type,
                is_active, is_superadmin
            ) VALUES (
                'chilanga.admin', '{noop}dev', 'EMP-COUNCIL-ADM',
                'ADMIN,PAYROLL', 'PAYROLL_CLERK', ?, 'TOWN', TRUE, FALSE
            )
            """, CHILANGA);
    }

    @Test
    @DisplayName("Superadmin can call /federation/ping → 200 with PLATFORM context")
    void superadminCanPing() throws Exception {
        mockMvc.perform(get("/federation/ping")
                        .header("Authorization", "Bearer " + jwtFor("admin.superadmin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.caller").value("admin.superadmin"))
                .andExpect(jsonPath("$.authority").value("PLATFORM"))
                .andExpect(jsonPath("$.superadmin").value(true));
    }

    @Test
    @DisplayName("Council admin cannot call /federation/ping → 403")
    void councilAdminCannotPing() throws Exception {
        mockMvc.perform(get("/federation/ping")
                        .header("Authorization", "Bearer " + jwtFor("chilanga.admin")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Anonymous cannot call /federation/ping → 401")
    void anonymousCannotPing() throws Exception {
        mockMvc.perform(get("/federation/ping"))
                .andExpect(status().isUnauthorized());
    }

    private String jwtFor(String username) {
        UserDetailsService uds = applicationContext.getBean(UserDetailsService.class);
        var user = uds.loadUserByUsername(username);
        var jwtService = applicationContext.getBean(com.localgov.web.security.JwtService.class);
        return jwtService.generateToken(user);
    }
}
