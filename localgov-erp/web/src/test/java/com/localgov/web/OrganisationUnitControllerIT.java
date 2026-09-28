package com.localgov.web;

import com.localgov.web.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.security.core.userdetails.User.builder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@TestPropertySource(properties = {
        "jwt.secret=Q2hhbmdlVGhpc1RvQVN0cm9uZ0FuZExvbmdlckRldk9ubHlTZWNyZXRLZXlGb3JMb2NhbEdvdkVSUA==",
        "jwt.expiration-millis=3600000",
        "spring.datasource.url=jdbc:h2:mem:erp_org_unit_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Import(OrganisationUnitControllerIT.TestSecurityConfiguration.class)
class OrganisationUnitControllerIT {

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @org.springframework.beans.factory.annotation.Autowired
    private JwtService jwtService;

    private String adminToken;
    private String hrToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        adminToken = tokenFor("admin", "ADMIN");
        hrToken = tokenFor("hr", "HR");
        employeeToken = tokenFor("employee", "EMPLOYEE");
    }

    @Test
    void unauthenticatedUserCannotReadOrganisationUnits() throws Exception {
        mockMvc.perform(get("/api/organisation-units")
                        .param("authorityCode", "CHL"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanReadOrganisationUnits() throws Exception {
        mockMvc.perform(get("/api/organisation-units")
                        .param("authorityCode", "CHL")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isOk());
    }

    @Test
    void employeeCannotCreateOrganisationUnit() throws Exception {
        mockMvc.perform(post("/api/organisation-units")
                        .param("authorityCode", "CHL")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "unitCode": "FIN",
                                  "name": "Finance Department",
                                  "unitType": "DEPARTMENT"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateOrganisationUnit() throws Exception {
        mockMvc.perform(post("/api/organisation-units")
                        .param("authorityCode", "CHL")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "unitCode": "FIN",
                                  "name": "Finance Department",
                                  "unitType": "DEPARTMENT"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorityCode").value("CHL"))
                .andExpect(jsonPath("$.unitCode").value("FIN"))
                .andExpect(jsonPath("$.name").value("Finance Department"))
                .andExpect(jsonPath("$.unitType").value("DEPARTMENT"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void hrCanCreateOrganisationUnit() throws Exception {
        mockMvc.perform(post("/api/organisation-units")
                        .param("authorityCode", "CHL")
                        .header("Authorization", "Bearer " + hrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "unitCode": "HR",
                                  "name": "Human Resources Department",
                                  "unitType": "DEPARTMENT"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unitCode").value("HR"));
    }

    @Test
    void employeeCannotMoveOrganisationUnit() throws Exception {
        mockMvc.perform(put("/api/organisation-units/1/move")
                        .param("authorityCode", "CHL")
                        .header("Authorization", "Bearer " + employeeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "newParentUnitId": null
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotDeactivateOrganisationUnit() throws Exception {
        mockMvc.perform(post("/api/organisation-units/1/deactivate")
                        .param("authorityCode", "CHL")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotActivateOrganisationUnit() throws Exception {
        mockMvc.perform(post("/api/organisation-units/1/activate")
                        .param("authorityCode", "CHL")
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden());
    }

    private String tokenFor(String username, String role) {
        UserDetails userDetails = builder()
                .username(username)
                .password("ignored")
                .roles(role)
                .build();

        return jwtService.generateToken(userDetails);
    }

    @TestConfiguration
    static class TestSecurityConfiguration {

        @Bean
        @Primary
        UserDetailsService testUserDetailsService() {
            Map<String, UserDetails> users = Map.of(
                    "admin", User.builder()
                            .username("admin")
                            .password("ignored")
                            .roles("ADMIN")
                            .build(),

                    "hr", User.builder()
                            .username("hr")
                            .password("ignored")
                            .roles("HR")
                            .build(),

                    "employee", User.builder()
                            .username("employee")
                            .password("ignored")
                            .roles("EMPLOYEE")
                            .build()
            );

            return username -> {
                UserDetails user = users.get(username);

                if (user == null) {
                    throw new UsernameNotFoundException(
                            "Test user not found: " + username
                    );
                }

                return user;
            };
        }
    }
}
