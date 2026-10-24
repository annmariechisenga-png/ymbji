package com.localgov.web.controller;

import com.localgov.domain.model.FederationPayrollEvent;
import com.localgov.domain.model.PayrollAdjustment;
import com.localgov.domain.model.RetroactiveSalaryEvent;
import com.localgov.repository.FederationPayrollEventRepository;
import com.localgov.repository.PayrollAdjustmentRepository;
import com.localgov.repository.RetroactiveSalaryEventRepository;
import com.localgov.service.dto.RetroactiveSalaryEventResponse;
import com.localgov.service.payroll.RetroactivePayrollCalculator;
import com.localgov.web.dto.RetroactiveSalaryEventDetailResponse;
import com.localgov.web.dto.RetroactiveSalaryEventDetailResponse.AdjustmentSummary;
import com.localgov.web.security.TenantUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST endpoints for the 2026 CA retroactive payroll engine (V138.3).
 *
 * <p>Five endpoints covering the council-admin lifecycle:
 * <ol>
 *   <li>GET  /payroll/retroactive-events              — list my council's events</li>
 *   <li>GET  /payroll/retroactive-events/{id}         — detail + child adjustments</li>
 *   <li>POST /payroll/retroactive-events/import       — import a federation template</li>
 *   <li>POST /payroll/retroactive-events/{id}/process — run the calculator</li>
 *   <li>POST /payroll/retroactive-events/{id}/cancel  — abandon a DRAFT</li>
 * </ol>
 *
 * <p><b>Tenant isolation:</b> every endpoint reads {@code authorityCode}
 * from the authenticated principal and scopes its repository query by
 * it. RLS in the database enforces the same boundary; the controller
 * scoping is belt-and-braces so a misconfigured RLS fails closed here
 * too. Ownership violations return 404 (not 403) so attackers can't
 * probe for existence of other councils' events.
 */
@RestController
@RequestMapping("/payroll/retroactive-events")
@Validated
@Tag(name = "Retroactive Payroll Events",
     description = "2026 CA back-pay: import, process, and review events")
public class RetroactiveSalaryEventController {

    private final FederationPayrollEventRepository templateRepository;
    private final RetroactiveSalaryEventRepository eventRepository;
    private final PayrollAdjustmentRepository adjustmentRepository;
    private final RetroactivePayrollCalculator calculator;

    public RetroactiveSalaryEventController(
            FederationPayrollEventRepository templateRepository,
            RetroactiveSalaryEventRepository eventRepository,
            PayrollAdjustmentRepository adjustmentRepository,
            RetroactivePayrollCalculator calculator) {
        this.templateRepository = templateRepository;
        this.eventRepository = eventRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.calculator = calculator;
    }

    // ---------------------------------------------------------------
    // GET /payroll/retroactive-events — list
    // ---------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL','HR','AUDITOR')")
    @Operation(summary = "List my council's retroactive events (most recent first)")
    public List<RetroactiveSalaryEventResponse> list(Authentication authentication) {
        String authority = callerAuthority(authentication);
        return eventRepository.findByAuthorityCodeOrderByImportedAtDesc(authority)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ---------------------------------------------------------------
    // GET /payroll/retroactive-events/{id} — detail
    // ---------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL','HR','AUDITOR')")
    @Operation(summary = "Detail of one event, plus the per-employee adjustments it created")
    public RetroactiveSalaryEventDetailResponse detail(
            @PathVariable Long id, Authentication authentication) {
        String authority = callerAuthority(authentication);
        RetroactiveSalaryEvent event = requireOwned(id, authority);

        List<AdjustmentSummary> summaries = adjustmentRepository
                .findByRetroactiveEventIdAndAuthorityCodeOrderByEmployeeIdAsc(id, authority)
                .stream()
                .map(this::toSummary)
                .toList();

        return new RetroactiveSalaryEventDetailResponse(toResponse(event), summaries);
    }

    // ---------------------------------------------------------------
    // POST /payroll/retroactive-events/import — create from template
    // ---------------------------------------------------------------

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import a federation template as this council's event")
    public RetroactiveSalaryEventResponse importEvent(
            @Valid @RequestBody ImportRequest body, Authentication authentication) {
        String authority = callerAuthority(authentication);
        String importer = callerUsername(authentication);

        FederationPayrollEvent template = templateRepository.findById(body.templateId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Federation template not found: " + body.templateId()));

        if (!Boolean.TRUE.equals(template.getIsActive())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Template is inactive: " + template.getEventCode());
        }

        // Unique constraint (authority_code, template_id) also enforces this,
        // but we check first for a friendly 409 instead of a DB-level 500.
        eventRepository.findByAuthorityCodeAndTemplateId(authority, template.getId())
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Council " + authority + " already imported " + template.getEventCode()
                                    + " (event id " + existing.getId() + ")");
                });

        RetroactiveSalaryEvent event = new RetroactiveSalaryEvent();
        event.setAuthorityCode(authority);
        event.setTemplateId(template.getId());
        event.setEventCode(template.getEventCode());
        event.setStatus("DRAFT");
        event.setImportedBy(importer);
        eventRepository.save(event);

        return toResponse(event);
    }

    // ---------------------------------------------------------------
    // POST /payroll/retroactive-events/{id}/process — run calculator
    // ---------------------------------------------------------------

    @PostMapping("/{id}/process")
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL')")
    @Operation(summary = "Process a DRAFT event: compute arrears and create adjustments")
    public RetroactiveSalaryEventDetailResponse process(
            @PathVariable Long id, Authentication authentication) {
        String authority = callerAuthority(authentication);
        RetroactiveSalaryEvent event = requireOwned(id, authority);

        // Idempotency: PROCESSED is OK (calculator returns as-is).
        // CANCELLED / PROCESSING is not.
        String status = event.getStatus();
        if ("CANCELLED".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Cannot process a CANCELLED event");
        }
        if ("PROCESSING".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Event is already PROCESSING");
        }

        calculator.computeForEvent(id);

        // Re-read to get the post-process state (employeeCount, totalAmount)
        RetroactiveSalaryEvent refreshed = requireOwned(id, authority);
        List<AdjustmentSummary> summaries = adjustmentRepository
                .findByRetroactiveEventIdAndAuthorityCodeOrderByEmployeeIdAsc(id, authority)
                .stream()
                .map(this::toSummary)
                .toList();

        return new RetroactiveSalaryEventDetailResponse(toResponse(refreshed), summaries);
    }

    // ---------------------------------------------------------------
    // POST /payroll/retroactive-events/{id}/cancel — abandon a DRAFT
    // ---------------------------------------------------------------

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','PAYROLL')")
    @Operation(summary = "Cancel a DRAFT event (no adjustments are created)")
    public RetroactiveSalaryEventResponse cancel(
            @PathVariable Long id, Authentication authentication) {
        String authority = callerAuthority(authentication);
        RetroactiveSalaryEvent event = requireOwned(id, authority);

        if (!"DRAFT".equals(event.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only DRAFT events can be cancelled (was " + event.getStatus() + ")");
        }

        event.setStatus("CANCELLED");
        eventRepository.save(event);
        return toResponse(event);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Load the event and verify it belongs to the caller's council.
     * Returns 404 on both not-found and wrong-tenant so callers cannot
     * distinguish "doesn't exist" from "exists but isn't yours".
     */
    private RetroactiveSalaryEvent requireOwned(Long id, String authority) {
        return eventRepository.findById(id)
                .filter(e -> authority.equals(e.getAuthorityCode()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Event not found: " + id));
    }

    private String callerAuthority(Authentication authentication) {
        TenantUserDetails user = (TenantUserDetails) authentication.getPrincipal();
        String authority = user.getAuthorityCode();
        if (authority == null || authority.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Authenticated user has no tenant");
        }
        return authority;
    }

    private String callerUsername(Authentication authentication) {
        return authentication.getName();
    }

    private RetroactiveSalaryEventResponse toResponse(RetroactiveSalaryEvent e) {
        // eventName is the template's human-readable name; fall back to code if template gone.
        String name = templateRepository.findById(e.getTemplateId())
                .map(FederationPayrollEvent::getEventName)
                .orElse(e.getEventCode());

        return new RetroactiveSalaryEventResponse(
                e.getId(),
                e.getAuthorityCode(),
                e.getTemplateId(),
                e.getEventCode(),
                name,
                e.getStatus(),
                e.getImportedBy(),
                e.getImportedAt(),
                e.getProcessingStartedAt(),
                e.getProcessedAt(),
                e.getProcessedBy(),
                e.getEmployeeCount(),
                e.getTotalAmount(),
                e.getNotes()
        );
    }

    private AdjustmentSummary toSummary(PayrollAdjustment a) {
        return new AdjustmentSummary(
                a.getId(),
                a.getEmployeeId(),
                a.getAdjustmentType() != null ? a.getAdjustmentType().name() : null,
                a.getDirection() != null ? a.getDirection().name() : null,
                a.getAmount(),
                a.getStatus() != null ? a.getStatus().name() : null,
                a.getPeriodFrom(),
                a.getPeriodTo(),
                a.getReason(),
                a.getAppliedRunId(),
                a.getAppliedAt()
        );
    }

    /** Request body for the import endpoint. */
    public record ImportRequest(@NotNull Long templateId) {
    }
}
