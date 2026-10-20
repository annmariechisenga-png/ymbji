package com.localgov.service;

import com.localgov.service.notification.SmsGateway;
import com.localgov.domain.model.OvertimeSession;
import com.localgov.repository.CommutedOvertimeRepository;
import com.localgov.repository.EmployeeRepository;
import com.localgov.repository.OvertimeSessionRepository;
import com.localgov.repository.OvertimeTeamRepository;
import com.localgov.repository.PayrollConfigRepository;
import com.localgov.repository.PublicHolidayRepository;
import com.localgov.service.exception.BusinessValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * State-machine tests for the OvertimeService approval workflow.
 *
 * <p>The paper workflow has 4 approval signatures after the officer's
 * submission:
 * <pre>
 *   pending_supervisor  ──approveBySupervisor──▶  pending_hod
 *   pending_hod         ──approveByHod────────▶  pending_audit
 *   pending_audit       ──approveByAuditor────▶  pending_ro
 *   pending_ro          ──approveByRO─────────▶  approved
 * </pre>
 * Reject at any pending stage → rejected.
 *
 * <p>Each happy path asserts on the mutated entity (via ArgumentCaptor)
 * because that proves both the state transition and the workflow fields
 * are correctly set — the whole point of the entity patch.
 */
@ExtendWith(MockitoExtension.class)
class OvertimeServiceStateMachineTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private OvertimeSessionRepository overtimeSessionRepository;
    @Mock private CommutedOvertimeRepository commutedOvertimeRepository;
    @Mock private PublicHolidayRepository publicHolidayRepository;
    @Mock private OvertimeTeamRepository overtimeTeamRepository;
    @Mock private PayrollConfigRepository payrollConfigRepository;
    @Mock private SmsGateway smsGateway;

    private OvertimeService service;

    @BeforeEach
    void setUp() {
        service = new OvertimeService(
            employeeRepository,
            overtimeSessionRepository,
            commutedOvertimeRepository,
            publicHolidayRepository,
            overtimeTeamRepository,
            payrollConfigRepository,
            smsGateway
        );
    }

    // ── Happy-path transitions ─────────────────────────────────────

    @Test
    void pendingSupervisor_approveBySupervisor_movesToPendingHod() {
        OvertimeSession session = sessionAt(1L, "pending_supervisor");
        session.setSupervisorId("SUP-001");
        stubFindAndSave(session);

        service.approveBySupervisor(1L, "SUP-001");

        OvertimeSession saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo("pending_hod");
        assertThat(saved.getSupervisorApproved()).isTrue();
        assertThat(saved.getSupervisorApprovedAt()).isNotNull();
    }

    @Test
    void pendingHod_approveByHod_movesToPendingAudit() {
        OvertimeSession session = sessionAt(2L, "pending_hod");
        stubFindAndSave(session);

        service.approveByHod(2L, "HOD-001");

        OvertimeSession saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo("pending_audit");
        assertThat(saved.getHodId()).isEqualTo("HOD-001");
        assertThat(saved.getHodApproved()).isTrue();
        assertThat(saved.getHodApprovedAt()).isNotNull();
    }

    @Test
    void pendingAudit_approveByAuditor_movesToPendingRo() {
        OvertimeSession session = sessionAt(3L, "pending_audit");
        stubFindAndSave(session);

        service.approveByAuditor(3L, "AUD-001");

        OvertimeSession saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo("pending_ro");
        assertThat(saved.getAuditorId()).isEqualTo("AUD-001");
        assertThat(saved.getAuditorApproved()).isTrue();
        assertThat(saved.getAuditorApprovedAt()).isNotNull();
    }

    @Test
    void pendingRo_approveByResponsibleOfficer_movesToApproved() {
        OvertimeSession session = sessionAt(4L, "pending_ro");
        stubFindAndSave(session);

        service.approveByResponsibleOfficer(4L, "RO-001");

        OvertimeSession saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo("approved");
        assertThat(saved.getResponsibleOfficerId()).isEqualTo("RO-001");
        assertThat(saved.getRoApproved()).isTrue();
        assertThat(saved.getRoApprovedAt()).isNotNull();
    }

    // ── Wrong-state guards ─────────────────────────────────────────

    @Test
    void approveByHod_fromPendingSupervisor_throws() {
        stubFind(sessionAt(5L, "pending_supervisor"));

        assertThatThrownBy(() -> service.approveByHod(5L, "HOD-001"))
            .isInstanceOf(BusinessValidationException.class)
            .hasMessageContaining("pending_hod");
    }

    @Test
    void approveByAuditor_fromPendingHod_throws() {
        stubFind(sessionAt(6L, "pending_hod"));

        assertThatThrownBy(() -> service.approveByAuditor(6L, "AUD-001"))
            .isInstanceOf(BusinessValidationException.class)
            .hasMessageContaining("pending_audit");
    }

    @Test
    void approveByResponsibleOfficer_fromPendingAudit_throws() {
        stubFind(sessionAt(7L, "pending_audit"));

        assertThatThrownBy(() -> service.approveByResponsibleOfficer(7L, "RO-001"))
            .isInstanceOf(BusinessValidationException.class)
            .hasMessageContaining("pending_ro");
    }

    @Test
    void approveBySupervisor_fromPendingHod_throws() {
        stubFind(sessionAt(8L, "pending_hod"));

        assertThatThrownBy(() -> service.approveBySupervisor(8L, "SUP-001"))
            .isInstanceOf(BusinessValidationException.class)
            .hasMessageContaining("pending_supervisor");
    }

    // ── Reject cases ───────────────────────────────────────────────

    @Test
    void rejectAtCurrentStage_fromEachPendingStage_marksRejected() {
        for (String status : new String[] {
            "pending_supervisor", "pending_hod", "pending_audit", "pending_ro" }) {
            OvertimeSession session = sessionAt(10L, status);
            stubFindAndSave(session);

            service.rejectAtCurrentStage(10L, "someone", "reason text");

            OvertimeSession saved = captureSaved();
            assertThat(saved.getStatus()).isEqualTo("rejected");
            assertThat(saved.getRejectionReason()).isEqualTo("reason text");
        }
    }

    @Test
    void rejectAtCurrentStage_fromApproved_throws() {
        stubFind(sessionAt(11L, "approved"));

        assertThatThrownBy(() -> service.rejectAtCurrentStage(11L, "x", "y"))
            .isInstanceOf(BusinessValidationException.class)
            .hasMessageContaining("cannot be rejected");
    }

    @Test
    void rejectAtCurrentStage_fromPaid_throws() {
        stubFind(sessionAt(12L, "paid"));

        assertThatThrownBy(() -> service.rejectAtCurrentStage(12L, "x", "y"))
            .isInstanceOf(BusinessValidationException.class)
            .hasMessageContaining("cannot be rejected");
    }

    // ── Helpers ────────────────────────────────────────────────────

    private OvertimeSession sessionAt(Long id, String status) {
        OvertimeSession s = new OvertimeSession();
        s.setId(id);
        s.setEmployeeId("EMP-001");
        s.setStatus(status);
        s.setSupervisorApproved(false);
        s.setHodApproved(false);
        s.setAuditorApproved(false);
        s.setRoApproved(false);
        s.setPaid(false);
        return s;
    }

    /** For happy-path tests: stubs both the lookup and the save. */
    private void stubFindAndSave(OvertimeSession session) {
        stubFind(session);
        when(overtimeSessionRepository.save(any(OvertimeSession.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    /** For exception tests: stubs only the lookup. No save is expected. */
    private void stubFind(OvertimeSession session) {
        when(overtimeSessionRepository.findById(session.getId()))
            .thenReturn(Optional.of(session));
    }

    private OvertimeSession captureSaved() {
        ArgumentCaptor<OvertimeSession> captor =
            ArgumentCaptor.forClass(OvertimeSession.class);
        verify(overtimeSessionRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}