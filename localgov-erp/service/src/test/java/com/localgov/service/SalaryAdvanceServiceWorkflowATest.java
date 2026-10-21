package com.localgov.service;

import com.localgov.domain.model.Employee;
import com.localgov.domain.model.SalaryAdvanceDecision;
import com.localgov.domain.model.SalaryAdvanceRequest;
import com.localgov.domain.model.SalaryAdvanceRequestStatus;
import com.localgov.repository.AuthorityMasterRepository;
import com.localgov.repository.SalaryAdvanceDeductionRepository;
import com.localgov.repository.SalaryAdvancePolicyRepository;
import com.localgov.repository.SalaryAdvanceRequestRepository;
import com.localgov.repository.SalaryAdvanceWorkflowEventRepository;
import com.localgov.service.dto.SalaryAdvanceRequestCreateRequest;
import com.localgov.service.dto.SalaryAdvanceRoDecisionRequest;
import com.localgov.service.exception.BusinessValidationException;
import com.localgov.service.mapper.SalaryAdvanceMapper;
import com.localgov.service.payroll.AllowanceCalculator;
import com.localgov.service.payroll.SalaryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workflow A tests for SalaryAdvanceService.
 *
 * <p>Workflow A (2021 T&Cs §89-90):
 *   Officer submits → RO approves → Finance disburses
 *
 * <p>Covers the highest-risk paths: the pre-eligibility gate on
 * outstanding advances, the RO decision state machine, and rejection
 * of out-of-order decisions. Eligibility-gate arithmetic (gross cap,
 * installment cap) is deferred to the integration test in Phase C.2,
 * which seeds a real policy row and employee.
 */
@ExtendWith(MockitoExtension.class)
class SalaryAdvanceServiceWorkflowATest {

    @Mock private AuthorityMasterRepository authorityMasterRepository;
    @Mock private SalaryAdvanceRequestRepository salaryAdvanceRequestRepository;
    @Mock private SalaryAdvancePolicyRepository salaryAdvancePolicyRepository;
    @Mock private SalaryAdvanceWorkflowEventRepository salaryAdvanceWorkflowEventRepository;
    @Mock private SalaryAdvanceDeductionRepository salaryAdvanceDeductionRepository;
    @Mock private EmployeeService employeeService;
    @Mock private SalaryAdvanceMapper salaryAdvanceMapper;
    @Mock private SalaryResolver salaryResolver;
    @Mock private AllowanceCalculator allowanceCalculator;

    @InjectMocks private SalaryAdvanceService service;

    // -----------------------------------------------------------------
    // submitRequest — pre-eligibility gate on outstanding advance
    // -----------------------------------------------------------------

    @Test
    @DisplayName("submitRequest: rejects when an active advance request exists")
    void submitRequest_rejectsActiveAdvance() {
        Employee emp = new Employee();
        emp.setEmployeeId("EMP-001");
        emp.setDateOfFirstAppointment(java.time.LocalDate.now().minusYears(2));

        when(employeeService.getEmployeeEntity("EMP-001")).thenReturn(emp);
        when(salaryAdvanceRequestRepository.existsByEmployeeIdAndStatusIn(
                eq("EMP-001"), any()))
            .thenReturn(true);

        SalaryAdvanceRequestCreateRequest req = new SalaryAdvanceRequestCreateRequest(
            "EMP-001", "CHILANGA", new BigDecimal("3000.00"), 3,
            "School fees for children", "Test Officer");

        assertThatThrownBy(() -> service.submitRequest(req))
            .isInstanceOf(BusinessValidationException.class)
            .hasMessageContaining("active salary advance request");

        // Verify we never proceeded to persist
        verify(salaryAdvanceRequestRepository, never()).save(any());
    }

    // -----------------------------------------------------------------
    // roDecision — state machine
    // -----------------------------------------------------------------

    @Test
    @DisplayName("roDecision APPROVED: PENDING_RO_APPROVAL -> APPROVED_FOR_DISBURSEMENT")
    void roDecision_approved_transitions() {
        SalaryAdvanceRequest request = pendingRoRequest(42L);
        when(salaryAdvanceRequestRepository.findById(42L)).thenReturn(Optional.of(request));
        when(salaryAdvanceRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SalaryAdvanceRoDecisionRequest decision = new SalaryAdvanceRoDecisionRequest(
            "Jane RO", SalaryAdvanceDecision.APPROVED, "Approved for school fees");

        service.roDecision(42L, decision);

        verify(salaryAdvanceRequestRepository).save(request);
        org.assertj.core.api.Assertions.assertThat(request.getStatus())
            .isEqualTo(SalaryAdvanceRequestStatus.APPROVED_FOR_DISBURSEMENT);
        org.assertj.core.api.Assertions.assertThat(request.getFinanceDecision())
            .isEqualTo(SalaryAdvanceDecision.APPROVED);
        org.assertj.core.api.Assertions.assertThat(request.getFinanceOfficerName())
            .isEqualTo("Jane RO");
    }

    @Test
    @DisplayName("roDecision REJECTED: PENDING_RO_APPROVAL -> REJECTED_BY_RO")
    void roDecision_rejected_transitions() {
        SalaryAdvanceRequest request = pendingRoRequest(43L);
        when(salaryAdvanceRequestRepository.findById(43L)).thenReturn(Optional.of(request));
        when(salaryAdvanceRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SalaryAdvanceRoDecisionRequest decision = new SalaryAdvanceRoDecisionRequest(
            "Jane RO", SalaryAdvanceDecision.REJECTED, "Insufficient merit");

        service.roDecision(43L, decision);

        org.assertj.core.api.Assertions.assertThat(request.getStatus())
            .isEqualTo(SalaryAdvanceRequestStatus.REJECTED_BY_RO);
    }

    @Test
    @DisplayName("roDecision: rejects when request is not PENDING_RO_APPROVAL")
    void roDecision_wrongStatus_throws() {
        SalaryAdvanceRequest request = pendingRoRequest(44L);
        request.setStatus(SalaryAdvanceRequestStatus.APPROVED_FOR_DISBURSEMENT); // already approved
        when(salaryAdvanceRequestRepository.findById(44L)).thenReturn(Optional.of(request));

        SalaryAdvanceRoDecisionRequest decision = new SalaryAdvanceRoDecisionRequest(
            "Jane RO", SalaryAdvanceDecision.APPROVED, null);

        assertThatThrownBy(() -> service.roDecision(44L, decision))
            .isInstanceOf(BusinessValidationException.class)
            .hasMessageContaining("pending RO approval");

        verify(salaryAdvanceRequestRepository, never()).save(any());
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private SalaryAdvanceRequest pendingRoRequest(Long id) {
        SalaryAdvanceRequest r = new SalaryAdvanceRequest();
        r.setId(id);
        r.setRequestNumber("SA-TEST-" + id);
        Employee emp = new Employee();
        emp.setEmployeeId("EMP-001");
        r.setEmployee(emp);
        r.setRequestedAmount(new BigDecimal("3000.00"));
        r.setRequestedInstallments(3);
        r.setReason("Test");
        r.setStatus(SalaryAdvanceRequestStatus.PENDING_RO_APPROVAL);
        return r;
    }
}
