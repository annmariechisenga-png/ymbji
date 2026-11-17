package com.localgov.web.controller.hod;

import com.localgov.service.hod.RequisitionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V194 — Purchase requisition endpoints.
 */
@RestController
@RequestMapping("/requisitions")
public class RequisitionController {

    private final RequisitionService service;

    public RequisitionController(RequisitionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String authorityCode = (String) body.get("authorityCode");
        String departmentCode = (String) body.get("departmentCode");
        String justification = (String) body.get("justification");
        LocalDate requiredByDate = body.get("requiredByDate") != null
                ? LocalDate.parse((String) body.get("requiredByDate")) : null;
        String planRef = (String) body.get("procurementPlanReference");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> linesData = (List<Map<String, Object>>) body.get("lines");

        var userId = currentUserId();
        var employeeId = currentEmployeeId();

        List<RequisitionService.LineRequest> lines = linesData.stream()
                .map(l -> new RequisitionService.LineRequest(
                        UUID.fromString((String) l.get("budgetLineId")),
                        (String) l.get("itemDescription"),
                        (String) l.get("technicalSpecification"),
                        ((Number) l.get("quantity")).intValue(),
                        new BigDecimal(l.get("unitCost").toString())))
                .toList();

        var req = new RequisitionService.CreateRequest(
                authorityCode, departmentCode, employeeId, userId,
                justification, requiredByDate, planRef);

        UUID id = service.createDraft(req, lines);
        return ResponseEntity.ok(Map.of("requisitionId", id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getRequisition(id));
    }

    @GetMapping("/my-drafts")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> myDrafts() {
        return ResponseEntity.ok(service.listMyDrafts(currentUserId(), currentAuthorityCode()));
    }

    @GetMapping("/department/{deptCode}")
    @PreAuthorize("hasAnyRole('HR','ADMIN','FINANCE','HEAD','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> departmentRequisitions(
            @PathVariable String deptCode) {
        return ResponseEntity.ok(service.listDepartmentRequisitions(
                currentAuthorityCode(), deptCode));
    }

    @GetMapping("/queues/hod")
    @PreAuthorize("hasAnyRole('HEAD','ADMIN','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> hodQueue() {
        return ResponseEntity.ok(service.hodWorkQueue(currentAuthorityCode()));
    }

    @GetMapping("/queues/finance")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> financeQueue() {
        return ResponseEntity.ok(service.financeWorkQueue(currentAuthorityCode()));
    }

    @GetMapping("/queues/controlling-officer")
    @PreAuthorize("hasAnyRole('CONTROLLING_OFFICER','ADMIN','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> coQueue() {
        return ResponseEntity.ok(service.controllingOfficerWorkQueue(currentAuthorityCode()));
    }

    // ---------------------------------------------------------------
    // V194b: role-based filter endpoints (consumed by the list page)
    // ---------------------------------------------------------------

    @GetMapping("/my-submissions")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN','CONTROLLING_OFFICER')")
    public ResponseEntity<List<Map<String, Object>>> mySubmissions() {
        return ResponseEntity.ok(service.listSubmittedByMe(
                currentUserId(), currentAuthorityCode()));
    }

    @GetMapping("/assigned-to-me")
    @PreAuthorize("hasAnyRole('HEAD','FINANCE','CONTROLLING_OFFICER','ADMIN','SUPERADMIN')")
    public ResponseEntity<List<Map<String, Object>>> assignedToMe(
            @RequestParam(value = "department", required = false) String department) {
        return ResponseEntity.ok(service.listAssignedToMe(
                currentUserId(), currentPrimaryRole(),
                currentAuthorityCode(), department));
    }

    @GetMapping("/visible")
    @PreAuthorize("hasAnyRole('EMPLOYEE','HR','ADMIN','FINANCE','HEAD','SUPERADMIN','CONTROLLING_OFFICER')")
    public ResponseEntity<List<Map<String, Object>>> visible(
            @RequestParam(value = "department", required = false) String department) {
        return ResponseEntity.ok(service.listForUser(
                currentUserId(), currentPrimaryRole(),
                currentAuthorityCode(), department));
    }

    // ---------------------------------------------------------------

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.localgov.web.security.TenantUser tu) {
            return tu.getUserId();
        }
        throw new IllegalStateException("No authenticated user");
    }

    private String currentEmployeeId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.localgov.web.security.TenantUser tu) {
            return tu.getEmployeeId();
        }
        throw new IllegalStateException("No employee in session");
    }

    private String currentAuthorityCode() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.localgov.web.security.TenantUser tu) {
            return tu.getAuthorityCode();
        }
        throw new IllegalStateException("No authority in session");
    }

    private String currentPrimaryRole() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            return auth.getAuthorities().stream()
                    .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                    .findFirst()
                    .orElse("UNKNOWN");
        }
        return "UNKNOWN";
    }
}
