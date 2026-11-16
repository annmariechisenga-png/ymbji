package com.localgov.service.payroll;

import com.localgov.service.dto.PayrollSimulationRequest;
import com.localgov.service.dto.PayrollSimulationResponse;
import com.localgov.service.payroll.model.AllowanceLine;
import com.localgov.service.payroll.model.ResolvedSalary;
import com.localgov.service.payroll.model.StatutoryDeductions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V194b — Hypothetical payroll simulation.
 *
 * Uses the refactored *FromMap variants of the calculators to compute
 * a full payslip breakdown from a synthetic employee profile, WITHOUT
 * writing to the employees table.
 *
 * Still reads reference tables (erp_salary_notch_value, erp_allowance_type,
 * compliance_rule, authorities.hardship_classification) — same as a real
 * payroll run.
 */
@Service
public class HypotheticalPayrollService {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    private static final String SYNTHETIC_ID = "SIMULATED";

    private final JdbcTemplate jdbc;
    private final SalaryResolver salaryResolver;
    private final AllowanceCalculator allowanceCalculator;
    private final StatutoryCalculator statutoryCalculator;

    public HypotheticalPayrollService(JdbcTemplate jdbc,
                                      SalaryResolver salaryResolver,
                                      AllowanceCalculator allowanceCalculator,
                                      StatutoryCalculator statutoryCalculator) {
        this.jdbc = jdbc;
        this.salaryResolver = salaryResolver;
        this.allowanceCalculator = allowanceCalculator;
        this.statutoryCalculator = statutoryCalculator;
    }

    public PayrollSimulationResponse simulate(PayrollSimulationRequest req) {

        // 1. Build a synthetic employee map matching loadEmployee()'s output shape
        Map<String, Object> empMap = buildSyntheticEmployeeMap(req);

        // 2. Resolve salary via resolveFromMap (no employees table lookup)
        ResolvedSalary salary = salaryResolver.resolveFromMap(
                empMap, SYNTHETIC_ID, req.authorityCode(),
                req.periodStart(), req.periodStart());

        // 3. Build EmployeeAttributes for allowance computation
        AllowanceCalculator.EmployeeAttributes empAttrs =
                new AllowanceCalculator.EmployeeAttributes(
                        req.salaryScale(),
                        req.bargainingUnit(),
                        req.department() != null ? req.department() : "",
                        req.position(),
                        false,   // hasDisability
                        null,    // disabilityCertificateKey
                        false    // disabilityVerified
                );

        // 4. Compute allowances via computeFromMap
        List<AllowanceLine> allowances = allowanceCalculator.computeFromMap(
                empAttrs, SYNTHETIC_ID, req.authorityCode(), salary, req.periodStart());

        // 5. Compute statutory deductions via computeFromMap
        BigDecimal grossAllowances = allowances.stream()
                .map(AllowanceLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, MONEY_ROUNDING);

        BigDecimal housingAllowance = allowances.stream()
                .filter(a -> "HOUSING".equals(a.allowanceCode()))
                .map(AllowanceLine::amount)
                .findFirst()
                .orElse(BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING));

        LocalDate firstAppointment = req.periodStart().minusYears(5); // assume 5-year tenure

        StatutoryDeductions statutory = statutoryCalculator.computeFromMap(
                SYNTHETIC_ID, req.authorityCode(), salary, req.periodStart(),
                req.periodStart().getYear(),
                grossAllowances, housingAllowance, firstAppointment);

        // 6. Compute union dues (management = 0; unionized = 2% of basic)
        BigDecimal unionDues = computeUnionDues(req.bargainingUnit(), salary.proratedBasic());

        // 7. Build response
        return buildResponse(req, salary, allowances, statutory, unionDues);
    }

    // ---------------------------------------------------------------
    // Synthetic employee map (matches loadEmployee query shape)
    // ---------------------------------------------------------------

    private Map<String, Object> buildSyntheticEmployeeMap(PayrollSimulationRequest req) {
        Map<String, Object> emp = new LinkedHashMap<>();
        emp.put("salary_scale", req.salaryScale());
        emp.put("current_notch", req.notchNo());
        // Assume a 5-year tenure by default (avoids proration for new hires)
        emp.put("date_reported", req.periodStart().minusYears(5));
        emp.put("termination_date", null);
        return emp;
    }

    // ---------------------------------------------------------------
    // Union dues (management = 0, unionized = 2% of basic)
    // ---------------------------------------------------------------

    private BigDecimal computeUnionDues(String bargainingUnit, BigDecimal basic) {
        if ("MANAGEMENT".equals(bargainingUnit)) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        }
        // UNION_DUES_RATE = 2% of basic for unionized bargaining units
        return basic.multiply(new BigDecimal("0.02"))
                .setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    // ---------------------------------------------------------------
    // Response assembly
    // ---------------------------------------------------------------

    private PayrollSimulationResponse buildResponse(
            PayrollSimulationRequest req,
            ResolvedSalary salary,
            List<AllowanceLine> allowances,
            StatutoryDeductions statutory,
            BigDecimal unionDues) {

        List<PayrollSimulationResponse.AllowanceBreakdown> allowanceBreakdowns = new ArrayList<>();
        BigDecimal totalAllowances = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);
        for (AllowanceLine a : allowances) {
            allowanceBreakdowns.add(new PayrollSimulationResponse.AllowanceBreakdown(
                    a.allowanceCode(), a.allowanceName(), a.amount(), a.calculationMethod()));
            totalAllowances = totalAllowances.add(a.amount());
        }

        BigDecimal basic = salary.proratedBasic();
        BigDecimal gross = basic.add(totalAllowances).setScale(MONEY_SCALE, MONEY_ROUNDING);

        BigDecimal totalEmployeeDeductions = statutory.paye()
                .add(statutory.napsaEmployee())
                .add(statutory.nhimaEmployee())
                .add(statutory.grzFuneralEmployee())
                .add(statutory.personalLevyEmployee())
                .add(unionDues)
                .setScale(MONEY_SCALE, MONEY_ROUNDING);

        BigDecimal net = gross.subtract(totalEmployeeDeductions).setScale(MONEY_SCALE, MONEY_ROUNDING);

        BigDecimal totalEmployerContributions = statutory.napsaEmployer()
                .add(statutory.nhimaEmployer())
                .add(statutory.grzFuneralEmployer())
                .add(statutory.lasfOccupationalEmployer())
                .setScale(MONEY_SCALE, MONEY_ROUNDING);

        BigDecimal totalMonthlyCost = gross.add(totalEmployerContributions)
                .setScale(MONEY_SCALE, MONEY_ROUNDING);

        return new PayrollSimulationResponse(
                req.authorityCode(),
                req.salaryScale(),
                req.notchNo(),
                req.bargainingUnit(),
                req.position(),
                basic,
                allowanceBreakdowns,
                gross,
                statutory.paye(),
                statutory.napsaEmployee(),
                statutory.nhimaEmployee(),
                statutory.grzFuneralEmployee(),
                statutory.personalLevyEmployee(),
                unionDues,
                totalEmployeeDeductions,
                net,
                statutory.napsaEmployer(),
                statutory.nhimaEmployer(),
                statutory.grzFuneralEmployer(),
                statutory.lasfOccupationalEmployer(),
                totalEmployerContributions,
                totalMonthlyCost,
                statutory.contributionScheme()
        );
    }
}
