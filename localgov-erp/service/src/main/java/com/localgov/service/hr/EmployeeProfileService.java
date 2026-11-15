package com.localgov.service.hr;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V193 — Employee self-service profile management.
 *
 * Employees can view and update their own profile sections (contact,
 * next of kin, bank). Personal details (name, DOB, NRC, gender) are
 * locked once onboarded — HR controls those.
 *
 * The scheme_type field is auto-derived from hire date but shown
 * read-only in the employee UI.
 */
@Service
public class EmployeeProfileService {

    private final JdbcTemplate jdbc;

    public EmployeeProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> getProfile(String employeeId) {
        Map<String, Object> employee = jdbc.queryForMap(
            "SELECT employee_id, authority_code, first_name, middle_name, last_name, " +
            "       sex, date_of_birth, nrc_masked, phone_masked, email, " +
            "       department, position, salary_scale, date_of_first_appointment, " +
            "       bank_name, branch_name, branch_code, bank_account_number_masked, " +
            "       next_of_kin_name, next_of_kin_phone_masked, next_of_kin_email, " +
            "       next_of_kin_relationship, house_plot_no, street, township, country, " +
            "       lasf_scheme_type " +
            "FROM employees WHERE employee_id = ?",
            employeeId);

        // Split into sections matching the LASF Form II structure
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("personalDetails", Map.of(
            "firstName", nullSafe(employee.get("first_name")),
            "middleName", nullSafe(employee.get("middle_name")),
            "lastName", nullSafe(employee.get("last_name")),
            "dateOfBirth", nullSafe(employee.get("date_of_birth")),
            "sex", nullSafe(employee.get("sex")),
            "nrcMasked", nullSafe(employee.get("nrc_masked")),
            "editable", false
        ));
        response.put("contact", Map.of(
            "housePlotNo", nullSafe(employee.get("house_plot_no")),
            "street", nullSafe(employee.get("street")),
            "township", nullSafe(employee.get("township")),
            "country", nullSafe(employee.get("country")),
            "email", nullSafe(employee.get("email")),
            "phoneMasked", nullSafe(employee.get("phone_masked")),
            "editable", true
        ));
        response.put("nextOfKin", Map.of(
            "name", nullSafe(employee.get("next_of_kin_name")),
            "phoneMasked", nullSafe(employee.get("next_of_kin_phone_masked")),
            "email", nullSafe(employee.get("next_of_kin_email")),
            "relationship", nullSafe(employee.get("next_of_kin_relationship")),
            "editable", true
        ));
        response.put("employment", Map.of(
            "employeeNumber", nullSafe(employee.get("employee_id")),
            "jobTitle", nullSafe(employee.get("position")),
            "department", nullSafe(employee.get("department")),
            "dateOfAppointment", nullSafe(employee.get("date_of_first_appointment")),
            "schemeType", nullSafe(employee.get("lasf_scheme_type")),
            "editable", false
        ));
        response.put("bank", Map.of(
            "bankName", nullSafe(employee.get("bank_name")),
            "branchName", nullSafe(employee.get("branch_name")),
            "branchCode", nullSafe(employee.get("branch_code")),
            "accountNumberMasked", nullSafe(employee.get("bank_account_number_masked")),
            "editable", true
        ));

        return response;
    }

    @Transactional
    public void updateSection(String employeeId, String section, Map<String, Object> body) {
        switch (section) {
            case "contact" -> jdbc.update(
                "UPDATE employees SET " +
                "  house_plot_no = ?, street = ?, township = ?, " +
                "  country = COALESCE(?, country), email = COALESCE(?, email) " +
                "WHERE employee_id = ?",
                body.get("housePlotNo"), body.get("street"),
                body.get("township"), body.get("country"),
                body.get("email"), employeeId);
            case "nextOfKin" -> jdbc.update(
                "UPDATE employees SET " +
                "  next_of_kin_name = ?, next_of_kin_email = COALESCE(?, next_of_kin_email), " +
                "  next_of_kin_relationship = ? " +
                "WHERE employee_id = ?",
                body.get("name"), body.get("email"),
                body.get("relationship"), employeeId);
            case "bank" -> jdbc.update(
                "UPDATE employees SET " +
                "  bank_name = ?, branch_name = ?, branch_code = ? " +
                "WHERE employee_id = ?",
                body.get("bankName"), body.get("branchName"),
                body.get("branchCode"), employeeId);
            default -> throw new IllegalArgumentException(
                "Unknown section: " + section + ". " +
                "Only 'contact', 'nextOfKin', 'bank' are editable.");
        }
    }

    private String nullSafe(Object o) {
        return o == null ? "" : o.toString();
    }
}
