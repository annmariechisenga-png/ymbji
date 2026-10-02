package com.localgov.web.leave;

import com.localgov.service.leave.LeaveCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "jwt.secret=Q2hhbmdlVGhpc1RvQVN0cm9uZ0FuZExvbmdlckRldk9ubHlTZWNyZXRLZXlGb3JMb2NhbEdvdkVSUA==",
        "jwt.expiration-millis=3600000",
        "spring.datasource.url=jdbc:h2:mem:erp_leave_cal_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class LeaveCalendarServiceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeaveCalendarService leaveCalendarService;

    @BeforeEach
    void seedPolicies() {
        jdbc.execute("DELETE FROM leave_policy");
        jdbc.execute("DELETE FROM public_holidays");

        jdbc.update("INSERT INTO leave_policy (leave_type,division,accrual_rate,max_accumulation,advance_notice,advance_notice_days,gender_restriction,day_calculation_mode,requires_birth_proof,requires_medical_cert,continuous_leave_limit) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "Vacation Leave", "Division I", 3.5, 230, 30, 30, "ALL", "WORKING", false, false, 120);

        // Friday start scenario: 2026-08-07 to 2026-09-11 includes multiple weekends and two public holidays
        jdbc.update("INSERT INTO public_holidays (holiday_date, name, created_at) VALUES (?, ?, NOW())",
                java.sql.Date.valueOf("2026-08-17"), "Farmers' Day");
        jdbc.update("INSERT INTO public_holidays (holiday_date, name, created_at) VALUES (?, ?, NOW())",
                java.sql.Date.valueOf("2026-09-07"), "Heritage Day");
    }

    @Test
    void calendarServiceExcludesWeekendsAndHolidays() {
        LocalDate start = LocalDate.of(2026, 8, 3);   // Monday
        LocalDate end = LocalDate.of(2026, 9, 18);     // Friday

        int workingDays = leaveCalendarService.countWorkingDays(start, end, "LA001");
        int weekends = leaveCalendarService.excludeWeekends(start, end);
        int holidays = leaveCalendarService.excludePublicHolidays(start, end, "LA001");
        LocalDate resume = leaveCalendarService.calculateResumeDate(end, "LA001");

        assertTrue(workingDays >= 30, "Expected at least 30 chargeable working days");
        assertTrue(weekends > 0, "Expected weekends to be excluded");
        assertTrue(holidays > 0, "Expected public holidays to be excluded");
        assertEquals(LocalDate.of(2026, 9, 21), resume, "Resume should be first working day after leave ends");
    }

    @Test
    void vacationLeaveStartingFridayQualifiesForAllowance() throws Exception {
        String token = login("hr", "Hr@123");
        String empId = createEmployee(token, "EMP-CAL-FRI", "fricalc@test.com", "Division I");
        jdbc.update("UPDATE leave_balances SET vacation_leave_balance = 200 WHERE employee_id = ?", Long.parseLong(empId));
        LocalDate startDate = nextFridayAfterAdvanceNoticeWindow();
        var expectedSchedule = leaveCalendarService.computeSchedule(startDate, 35, true, false, "Division I");

        mockMvc.perform(get("/leaves/calculate")
                        .header("Authorization", "Bearer " + token)
                        .param("employeeId", empId)
                        .param("leaveType", "VACATION")
                        .param("startDate", startDate.toString())
                        .param("requestedDays", "35"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeableDays").value(35))
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.reason").value("ELIGIBLE"))
                .andExpect(jsonPath("$.leaveEndDate").value(expectedSchedule.endDate().toString()))
                .andExpect(jsonPath("$.resumeDutiesDate").value(expectedSchedule.resumptionDate().toString()));
    }

    private String createEmployee(String token, String code, String email, String division) throws Exception {
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
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_balances WHERE employee_id = ?",
                Integer.class, Long.parseLong(id));
        if (existing == null || existing == 0) {
            jdbc.update("INSERT INTO leave_balances (employee_id, local_leave_balance, vacation_leave_balance) VALUES (?,?,?)",
                    Long.parseLong(id), 30, 30);
        }
        return id;
    }

    private LocalDate nextFridayAfterAdvanceNoticeWindow() {
        LocalDate base = LocalDate.now().plusDays(40);
        return base.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    }

    private String login(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

}
