package com.localgov.web.leave;

import com.localgov.domain.model.LeaveType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=Q2hhbmdlVGhpc1RvQVN0cm9uZ0FuZExvbmdlckRldk9ubHlTZWNyZXRLZXlGb3JMb2NhbEdvdkVSUA==",
        "jwt.expiration-millis=3600000",
        "spring.datasource.url=jdbc:h2:mem:erp_leave_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
@Sql(scripts = "/db/testmigration/init_leave_policy.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class LeaveWorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ── Seed canonical leave_policy rows before each test ─────────────────
    @BeforeEach
    void seedLeavePolicies() {
        jdbc.execute("DELETE FROM leave_policy");

        // Vacation Leave
        for (String[] row : new String[][]{
                {"Vacation Leave", "Division I",   "3.5", "230", "30"},
                {"Vacation Leave", "Division II",  "3.0", "205", "30"},
                {"Vacation Leave", "Division III", "2.5", "160", "30"},
                {"Vacation Leave", "Division IV",  "2.0", "160", "30"},
        }) {
            jdbc.update("INSERT INTO leave_policy (leave_type,division,accrual_rate,max_accumulation,advance_notice,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    row[0], row[1], Double.parseDouble(row[2]), Integer.parseInt(row[3]),
                    30, 30, "ALL", "WORKING", false, false);
        }

        // Local Leave
        for (String div : new String[]{"Division I","Division II","Division III","Division IV"}) {
            jdbc.update("INSERT INTO leave_policy (leave_type,division,accrual_rate,max_days,carry_forward,max_accumulation,max_duration,advance_notice,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    "Local Leave", div, 2.0, 30, 0, 30, 30, 0, 0, "ALL", "WORKING", false, false);
        }

        // Maternity
        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Maternity Leave", 98, 98, 0, "FEMALE", "CALENDAR", true, false);

        // Paternity
        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Paternity Leave", 10, 10, 0, "MALE", "CALENDAR", true, false);

        // Compassionate: max_duration=21(spouse), fixed_days=14(child/parent)
        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Compassionate Leave", 14, 21, 0, "ALL", "WORKING", false, false);

        // Family Care
        jdbc.update("INSERT INTO leave_policy (leave_type,max_duration,annual_limit,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Family Care Leave", 3, 3, 0, "ALL", "WORKING", false, false);

        // Mother's Day
        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,monthly_limit,annual_limit,carry_forward,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "Mother's Day Leave", 1, 1, 1, 12, 0, 0, "FEMALE", "WORKING", false, false);

        // Sick Leave
        jdbc.update("INSERT INTO leave_policy (leave_type,carry_forward,sick_full_pay_months,sick_half_pay_months,sick_full_pay_days_contract,sick_half_pay_days_contract,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "Sick Leave", 0, 3, 3, 26, 26, 0, "ALL", "CALENDAR", false, true);

        // Unpaid Leave
        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,max_accumulation,advance_notice,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "Unpaid Leave", 365, 365, 365, 30, 30, "ALL", "WORKING", false, false);

        // Study Leave
        jdbc.update("INSERT INTO leave_policy (leave_type,carry_forward,advance_notice,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Study Leave", 0, 30, 30, "ALL", "CALENDAR", false, false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Tests
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void localLeaveDeductsAccruedBalanceAndReturnsRemainingBalance() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-LOC-001", "local@test.com", "Division I");
        jdbc.update("UPDATE leave_balances SET local_leave_balance = 30 WHERE employee_id = ?",
                Long.parseLong(empId));
        LocalDate startDate = nextFutureWeekday(DayOfWeek.MONDAY);
        mockMvc.perform(post("/leaves")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + empId
                                + ",\"leaveType\":\"LOCAL\""
                                + ",\"startDate\":\"" + startDate + "\",\"daysRequested\":2"
                                + ",\"reason\":\"Family commitment\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(2))
                .andExpect(jsonPath("$.remainingBalance").value(28));
    }

    @Test
    void paternityLeaveUsesCalendarDaysAndDoesNotReduceBalance() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-PAT-001", "paternity@test.com", "Division I");
        jdbc.update("UPDATE erp_employee SET gender = 'male' WHERE id = ?", Long.parseLong(empId));

        MockMultipartFile file = new MockMultipartFile(
                "supportingDocument", "birth.pdf", "application/pdf", "dummy".getBytes());

        mockMvc.perform(multipart("/leaves")
                        .file(file)
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "PATERNITY")
                        .param("startDate", LocalDate.now().plusDays(7).toString())
                        .param("daysRequested", "10")
                        .param("reason", "New born child"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(10))
                .andExpect(jsonPath("$.deductedFromAccruedBalance").value(false));
    }

    @Test
    void maternityLeaveRequiresFemaleGender() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-MAT-001", "mat@test.com", "Division II");
        jdbc.update("UPDATE erp_employee SET gender = 'male' WHERE id = ?", Long.parseLong(empId));

        MockMultipartFile file = new MockMultipartFile(
                "supportingDocument", "birth.pdf", "application/pdf", "dummy".getBytes());

        mockMvc.perform(multipart("/leaves")
                        .file(file)
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "MATERNITY")
                        .param("startDate", LocalDate.now().plusDays(7).toString())
                        .param("daysRequested", "98")
                        .param("reason", "Maternity"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void familyCareLeaveIsLimitedToThreeDaysPerYear() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-FAM-001", "familycare@test.com", "Division III");
        LocalDate firstStartDate = nextFutureWeekday(DayOfWeek.MONDAY);
        LocalDate secondStartDate = firstStartDate.plusWeeks(4);

        mockMvc.perform(post("/leaves")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + empId
                                + ",\"leaveType\":\"FAMILY_CARE\""
                                + ",\"startDate\":\"" + firstStartDate + "\",\"daysRequested\":3"
                                + ",\"reason\":\"Family illness\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/leaves")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + empId
                                + ",\"leaveType\":\"FAMILY_CARE\""
                                + ",\"startDate\":\"" + secondStartDate + "\",\"daysRequested\":3"
                                + ",\"reason\":\"Another family care request\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Maximum Family Care leave (3 days) already reached for calendar year "
                                + firstStartDate.getYear() + "."));
    }

    @Test
    void familyCareAnnualLimitUsesLeaveYearNotApplicationYear() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-FAM-YEAR-001", "familycare-year@test.com", "Division III");

        // Consume the full 3-day Family Care entitlement in 2026.
        LocalDate leave2026Start = nextFutureWeekday(DayOfWeek.MONDAY);
        mockMvc.perform(post("/leaves")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + empId
                                + ",\"leaveType\":\"FAMILY_CARE\""
                                + ",\"startDate\":\"" + leave2026Start + "\",\"daysRequested\":3"
                                + ",\"reason\":\"Family care in 2026\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(3));

        // The application is being made in 2026, but the requested leave is
        // in 2027. The annual limit must therefore be evaluated for 2027.
        LocalDate leave2027Start = LocalDate.of(2027, 1, 4);
        mockMvc.perform(post("/leaves")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + empId
                                + ",\"leaveType\":\"FAMILY_CARE\""
                                + ",\"startDate\":\"" + leave2027Start + "\",\"daysRequested\":3"
                                + ",\"reason\":\"Family care in 2027\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(3));
    }

    @Test
    void studyLeaveIsCreatedAndUsesCalendarDays() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-STD-001", "study@test.com", "Division II");
        LocalDate startDate = LocalDate.now().plusDays(14);
        mockMvc.perform(post("/leaves")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + empId
                                + ",\"leaveType\":\"STUDY\""
                                + ",\"startDate\":\"" + startDate + "\",\"daysRequested\":7"
                                + ",\"reason\":\"Professional development course\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(7))
                .andExpect(jsonPath("$.deductedFromAccruedBalance").value(false));
    }

    @Test
    void sickLeaveUsesCalendarDaysAndIsConditionOfService() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-SICK-001", "sick@test.com", "Division I");
        LocalDate startDate = LocalDate.now().plusDays(10);
        mockMvc.perform(post("/leaves")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + empId
                                + ",\"leaveType\":\"SICK\""
                                + ",\"startDate\":\"" + startDate + "\",\"daysRequested\":5"
                                + ",\"reason\":\"Medical illness\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(5))
                .andExpect(jsonPath("$.deductedFromAccruedBalance").value(false));
    }

    @Test
    void vacationLeaveDeductsFromVacationBalance() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-VAC-001", "vac@test.com", "Division II");
        jdbc.update("UPDATE leave_balances SET vacation_leave_balance = 30 WHERE employee_id = ?",
                Long.parseLong(empId));
        LocalDate startDate = LocalDate.now().plusDays(40);
        mockMvc.perform(post("/leaves")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + empId
                                + ",\"leaveType\":\"VACATION\""
                                + ",\"startDate\":\"" + startDate + "\",\"daysRequested\":4"
                                + ",\"reason\":\"Annual holiday\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(4))
                .andExpect(jsonPath("$.balanceType").value("VACATION_LEAVE"));
    }

    @Test
    void leaveTypesEndpointDoesNotIncludeAnnualLeave() throws Exception {
        String token = login("hr", "Hr@123");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/leaves/leave-types")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='ANNUAL')]").isEmpty())
                .andExpect(jsonPath("$[?(@.code=='STUDY')]").isNotEmpty());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private LocalDate nextFutureWeekday(DayOfWeek dayOfWeek) {
        LocalDate today = LocalDate.now();
        LocalDate candidate = today.with(TemporalAdjusters.nextOrSame(dayOfWeek));
        if (!candidate.isAfter(today)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }

    private String createEmployee(String token, String code, String email, String division)
            throws Exception {
        String body = "{"
                + "\"employeeCode\":\"" + code + "\","
                + "\"firstName\":\"Test\",\"lastName\":\"User\","
                + "\"email\":\"" + email + "\","
                + "\"department\":\"HR\",\"positionTitle\":\"Officer\","
                + "\"baseSalary\":5000,\"hireDate\":\"2024-01-01\","
                + "\"role\":\"EMPLOYEE\","
                + "\"division\":\"" + division + "\""
                + "}";

        String response = mockMvc.perform(post("/employees")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":(\\d+).*", "$1");
        // Ensure a leave_balances row exists for balance-deducting tests
        // Insert balance row only if one does not already exist (H2-compatible)
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_balances WHERE employee_id = ?",
                Integer.class, Long.parseLong(id));
        if (existing == null || existing == 0) {
            jdbc.update("INSERT INTO leave_balances (employee_id, local_leave_balance, vacation_leave_balance) VALUES (?,?,?)",
                    Long.parseLong(id), 30, 30);
        }
        return id;
    }

    private String login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }
}
