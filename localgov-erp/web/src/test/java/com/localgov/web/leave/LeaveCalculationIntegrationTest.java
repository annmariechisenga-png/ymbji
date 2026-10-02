package com.localgov.web.leave;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=Q2hhbmdlVGhpc1RvQVN0cm9uZ0FuZExvbmdlckRldk9ubHlTZWNyZXRLZXlGb3JMb2NhbEdvdkVSUA==",
        "jwt.expiration-millis=3600000",
        "spring.datasource.url=jdbc:h2:mem:erp_leave_calc_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
@Sql(scripts = "/db/testmigration/init_leave_policy.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class LeaveCalculationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seedData() {
        jdbc.execute("DELETE FROM leave_policy");
        jdbc.execute("DELETE FROM public_holidays");

        for (String[] row : new String[][]{
                {"Vacation Leave", "Division I", "3.5", "230", "120"},
                {"Vacation Leave", "Division II", "3.0", "205", "110"},
                {"Vacation Leave", "Division III", "2.5", "160", "100"},
                {"Vacation Leave", "Division IV", "2.0", "160", "100"},
        }) {
            jdbc.update("INSERT INTO leave_policy (leave_type,division,accrual_rate,max_accumulation,advance_notice,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert,continuous_leave_limit) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    row[0], row[1], Double.parseDouble(row[2]), Integer.parseInt(row[3]),
                    30, 30, "ALL", "WORKING", false, false, Integer.parseInt(row[4]));
        }

        for (String div : new String[]{"Division I","Division II","Division III","Division IV"}) {
            jdbc.update("INSERT INTO leave_policy (leave_type,division,accrual_rate,max_days,carry_forward,max_accumulation,max_duration,advance_notice,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    "Local Leave", div, 2.0, 30, 0, 30, 30, 0, 0, "ALL", "WORKING", false, false);
        }

        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Maternity Leave", 98, 98, 0, "FEMALE", "CALENDAR", true, false);
        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Paternity Leave", 10, 10, 0, "MALE", "CALENDAR", true, false);
        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Compassionate Leave", 14, 21, 0, "ALL", "WORKING", false, false);
        jdbc.update("INSERT INTO leave_policy (leave_type,max_duration,annual_limit,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Family Care Leave", 3, 3, 0, "ALL", "WORKING", false, false);
        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,monthly_limit,annual_limit,carry_forward,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "Mother's Day Leave", 1, 1, 1, 12, 0, 0, "FEMALE", "WORKING", false, false);
        jdbc.update("INSERT INTO leave_policy (leave_type,carry_forward,sick_full_pay_months,sick_half_pay_months,sick_full_pay_days_contract,sick_half_pay_days_contract,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "Sick Leave", 0, 3, 3, 26, 26, 0, "ALL", "CALENDAR", false, true);
        jdbc.update("INSERT INTO leave_policy (leave_type,carry_forward,advance_notice,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?)",
                "Study Leave", 0, 30, 30, "ALL", "CALENDAR", false, false);
        jdbc.update("INSERT INTO leave_policy (leave_type,fixed_days,max_duration,max_accumulation,advance_notice,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "Unpaid Leave", 365, 365, 365, 30, 30, "ALL", "WORKING", false, false);

        jdbc.update("INSERT INTO public_holidays (holiday_date, name, created_at) VALUES (?, ?, NOW())",
                java.sql.Date.valueOf("2026-07-06"), "Heroes' Day");
        jdbc.update("INSERT INTO public_holidays (holiday_date, name, created_at) VALUES (?, ?, NOW())",
                java.sql.Date.valueOf("2026-07-07"), "Unity Day");
    }

    @Test
    void calculateLocalLeaveExcludesWeekendsAndHolidays() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-CALC-001", "calc1@test.com", "Division I");

        mockMvc.perform(get("/leaves/calculate")
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "LOCAL")
                        .param("startDate", "2026-07-01")
                        .param("requestedDays", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaveType").value("LOCAL"))
                .andExpect(jsonPath("$.calculationMode").value("Working Days"))
                .andExpect(jsonPath("$.chargeableDays").value(5))
                .andExpect(jsonPath("$.weekendsExcluded").value(true))
                .andExpect(jsonPath("$.startDate").value("2026-07-01"))
                .andExpect(jsonPath("$.leaveEndDate").value("2026-07-09"))
                .andExpect(jsonPath("$.weekendDaysSkipped").value(2))
                .andExpect(jsonPath("$.publicHolidaysSkipped").value(2))
                .andExpect(jsonPath("$.resumeDutiesDate").exists())
                .andExpect(jsonPath("$.deductsFromAccruedBalance").value(true));
    }

    @Test
    void calculateMaternityLeaveReturnsFixedDuration() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-CALC-002", "calc2@test.com", "Division II");
        jdbc.update("UPDATE erp_employee SET gender = 'female' WHERE id = ?", Long.parseLong(empId));

        mockMvc.perform(get("/leaves/calculate")
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "MATERNITY")
                        .param("startDate", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculationMode").value("Fixed Duration"))
                .andExpect(jsonPath("$.chargeableDays").value(98))
                .andExpect(jsonPath("$.weekendsExcluded").value(false))
                .andExpect(jsonPath("$.deductsFromAccruedBalance").value(false))
                .andExpect(jsonPath("$.leaveEndDate").value("2026-11-06"))
                .andExpect(jsonPath("$.resumeDutiesDate").value("2026-11-09"));
    }

    @Test
    void calculatePaternityLeaveReturnsFixedDuration() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-CALC-003", "calc3@test.com", "Division I");
        jdbc.update("UPDATE erp_employee SET gender = 'male' WHERE id = ?", Long.parseLong(empId));

        mockMvc.perform(get("/leaves/calculate")
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "PATERNITY")
                        .param("startDate", "2026-08-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculationMode").value("Fixed Duration"))
                .andExpect(jsonPath("$.chargeableDays").value(10))
                .andExpect(jsonPath("$.leaveEndDate").value("2026-08-12"))
                .andExpect(jsonPath("$.resumeDutiesDate").value("2026-08-13"));
    }

    @Test
    void calculateVacationEnforcesContinuousLimit() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-CALC-004", "calc4@test.com", "Division I");
        jdbc.update("UPDATE leave_balances SET vacation_leave_balance = 200 WHERE employee_id = ?", Long.parseLong(empId));
        LocalDate vacationStartDate = LocalDate.now().plusDays(40);

        mockMvc.perform(get("/leaves/calculate")
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "VACATION")
                        .param("startDate", vacationStartDate.toString())
                        .param("requestedDays", "130"))

                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continuousLeaveLimit").value(120))
                .andExpect(jsonPath("$.accumulationLimit").value(230))
                .andExpect(jsonPath("$.forfeitedDays").value(10))
                .andExpect(jsonPath("$.policyViolationFlag").value("CONTINUOUS_LEAVE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.chargeableDays").value(120));
    }

    @Test
    void calculateVacationReportsAccumulationExcess() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-CALC-005", "calc5@test.com", "Division II");
        jdbc.update("UPDATE leave_balances SET vacation_leave_balance = 250 WHERE employee_id = ?", Long.parseLong(empId));
        LocalDate vacationStartDate = LocalDate.now().plusDays(40);

        mockMvc.perform(get("/leaves/calculate")
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "VACATION")
                        .param("startDate", vacationStartDate.toString())
                        .param("requestedDays", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accumulationLimit").value(205))
                .andExpect(jsonPath("$.forfeitedDays").value(45))
                .andExpect(jsonPath("$.policyViolationFlag").value("ACCUMULATION_LIMIT_EXCEEDED"));
    }

    @Test
    void calculateCompassionateSpouse() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-CALC-006", "calc6@test.com", "Division I");

        mockMvc.perform(get("/leaves/calculate")
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "COMPASSIONATE")
                        .param("startDate", "2026-08-03")
                        .param("requestedDays", "15")
                        .param("compassionateRelation", "SPOUSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeableDays").value(15))
                .andExpect(jsonPath("$.deductsFromAccruedBalance").value(false));
    }

    @Test
    void submitLeaveStoresResumptionDate() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-CALC-009", "calc9@test.com", "Division I");
        jdbc.update("UPDATE leave_balances SET local_leave_balance = 30 WHERE employee_id = ?", Long.parseLong(empId));
        LocalDate startDate = nextFutureWeekday(DayOfWeek.MONDAY);
        mockMvc.perform(post("/leaves")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":" + empId
                                + ",\"leaveType\":\"LOCAL\""
                                + ",\"startDate\":\"" + startDate + "\",\"daysRequested\":2"
                                + ",\"reason\":\"Test resumption date storage\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.daysRequested").value(2));

        String resumptionDate = jdbc.queryForObject(
                "SELECT CAST(resumption_date AS VARCHAR) FROM erp_leave_request WHERE employee_id = ?",
                String.class, Long.parseLong(empId));
        org.junit.jupiter.api.Assertions.assertEquals(startDate.plusDays(2).toString(), resumptionDate);
    }


    @Test
    void calculateLeaveDoesNotCreateAuditLogEntry() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-CALC-010", "calc10@test.com", "Division I");

        mockMvc.perform(get("/leaves/calculate")
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "LOCAL")
                        .param("startDate", "2026-08-03")
                        .param("requestedDays", "3"))
                .andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_calculation_audit_log WHERE employee_id = ?",
                Integer.class, Long.parseLong(empId));
        org.junit.jupiter.api.Assertions.assertNotNull(count);
        org.junit.jupiter.api.Assertions.assertEquals(0, count,
                "Preview calculations must not write to the audit log");
    }

    private LocalDate nextFutureWeekday(DayOfWeek dayOfWeek) {
        LocalDate today = LocalDate.now();
        LocalDate candidate = today.with(TemporalAdjusters.nextOrSame(dayOfWeek));
        if (!candidate.isAfter(today)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }

    private String createEmployee(String token, String code, String email, String division) throws Exception {
        String body = "{\"employeeCode\":\"" + code + "\",\"firstName\":\"Test\",\"lastName\":\"User\","
                + "\"email\":\"" + email + "\",\"department\":\"HR\",\"positionTitle\":\"Officer\","
                + "\"baseSalary\":5000,\"hireDate\":\"2024-01-01\",\"role\":\"EMPLOYEE\","
                + "\"division\":\"" + division + "\"}";
        String response = mockMvc.perform(post("/employees")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = response.replaceAll(".*\"id\":(\\d+).*", "$1");
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM leave_balances WHERE employee_id = ?", Integer.class, Long.parseLong(id));
        if (cnt == null || cnt == 0)
            jdbc.update("INSERT INTO leave_balances (employee_id, local_leave_balance, vacation_leave_balance) VALUES (?,?,?)", Long.parseLong(id), 30, 30);
        return id;
    }

    private String login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }
}
