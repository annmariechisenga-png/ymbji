package com.localgov.web.controller;

import com.localgov.domain.model.CompassionateLeaveRelation;
import com.localgov.domain.model.LeaveType;
import com.localgov.service.LeaveCalculationService;
import com.localgov.service.LeaveService;
import com.localgov.service.dto.LeaveCalculationRequest;
import com.localgov.service.dto.LeaveCalculationResult;
import com.localgov.service.dto.EmployeeLeaveBalanceResponse;
import com.localgov.service.dto.LeaveApprovalRequest;
import com.localgov.service.dto.LeaveCancellationRequest;
import com.localgov.service.dto.LeaveRequestCreateRequest;
import com.localgov.service.dto.LeaveRequestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/leaves")
@Validated
@Tag(name = "Leaves", description = "Leave request and approval endpoints")
public class LeaveController {

    private final LeaveService leaveService;
    private final LeaveCalculationService leaveCalculationService;

    public LeaveController(LeaveService leaveService, LeaveCalculationService leaveCalculationService) {
        this.leaveService = leaveService;
        this.leaveCalculationService = leaveCalculationService;
    }

    @GetMapping("/calculate")
    @PreAuthorize("hasAnyRole('ADMIN','HR','HEAD','MANAGER','EMPLOYEE')")
    @Operation(summary = "Preview leave calculation",
               description = "Pure read-only preview. No database writes.")
    public LeaveCalculationResult calculateLeave(
            @RequestParam @Positive Long employeeId,
            @RequestParam LeaveType leaveType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @Min(1) Integer requestedDays,
            @RequestParam(required = false) CompassionateLeaveRelation compassionateRelation
    ) {
        return leaveCalculationService.calculate(
                new LeaveCalculationRequest(employeeId, leaveType, startDate, requestedDays, compassionateRelation));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','HR','EMPLOYEE')")
    @Operation(summary = "Submit leave request", description = "Allowed roles: ADMIN, HR, EMPLOYEE")
    public LeaveRequestResponse submitLeaveRequest(@Valid @RequestBody LeaveRequestCreateRequest request) {
        return leaveService.submitLeaveRequest(request);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit leave request with supporting document", description = "Allowed roles: ADMIN, HR, EMPLOYEE")
    public LeaveRequestResponse submitLeaveRequestWithDocument(
            @RequestParam @Positive Long employeeId,
            @RequestParam LeaveType leaveType,
            @RequestParam(required = false) CompassionateLeaveRelation compassionateRelation,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @Min(1) Integer daysRequested,
            @RequestParam String reason,
            @RequestParam(value = "supportingDocument", required = false) MultipartFile supportingDocument
    ) {
        byte[] documentBytes = null;
        String documentName = null;
        String documentContentType = null;

        if (supportingDocument != null && !supportingDocument.isEmpty()) {
            documentName = supportingDocument.getOriginalFilename();
            documentContentType = supportingDocument.getContentType();
            try {
                documentBytes = supportingDocument.getBytes();
            } catch (IOException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read the uploaded supporting document.", exception);
            }
        }

        return leaveService.submitLeaveRequest(
            new LeaveRequestCreateRequest(employeeId, leaveType, compassionateRelation, startDate, daysRequested, reason),
                documentName,
                documentContentType,
                documentBytes
        );
    }

    @PatchMapping("/{id}/decision")
    @PreAuthorize("hasAnyRole('ADMIN','HR','HEAD','MANAGER')")
    @Operation(summary = "Approve or reject leave", description = "Allowed roles: ADMIN, HR, HEAD, MANAGER")
    public LeaveRequestResponse approveOrReject(@PathVariable @Positive Long id, @Valid @RequestBody LeaveApprovalRequest request) {
        return leaveService.approveOrReject(id, request);
    }

    @PatchMapping("/{id}/cancel")
        @Operation(
            summary = "Cancel leave request",
            description = "Allowed roles: ADMIN, HR, EMPLOYEE",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                required = true,
                content = @Content(
                    mediaType = "application/json",
                    examples = @ExampleObject(
                        name = "CancelLeaveRequestExample",
                        value = """
                            {
                              "cancelledBy": "HR Officer",
                              "reason": "Employee withdrew request"
                            }
                            """
                    )
                )
            )
        )
        @ApiResponses({
            @ApiResponse(
                responseCode = "200",
                description = "Leave request cancelled",
                content = @Content(
                    mediaType = "application/json",
                    examples = @ExampleObject(
                        name = "CancelLeaveResponseExample",
                        value = """
                            {
                              "id": 3,
                              "employeeId": 1,
                              "employeeCode": "EMP-1001",
                              "leaveType": "VACATION",
                              "status": "CANCELLED",
                              "startDate": "2026-03-20",
                              "endDate": "2026-03-22",
                              "daysRequested": 3,
                              "reason": "Medical checkup",
                              "approvedBy": "HR Officer",
                              "approvedAt": "2026-03-09T22:31:10.785209",
                              "createdAt": "2026-03-09T22:31:10.562791"
                            }
                            """
                    )
                )
            ),
            @ApiResponse(responseCode = "400", description = "Only pending leave requests can be cancelled"),
            @ApiResponse(responseCode = "404", description = "Leave request not found")
        })
    public LeaveRequestResponse cancelLeave(@PathVariable @Positive Long id, @Valid @RequestBody LeaveCancellationRequest request) {
        return leaveService.cancelLeaveRequest(id, request);
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('ADMIN','HR','HEAD','MANAGER')")
    @Operation(summary = "List pending leave requests", description = "Allowed roles: ADMIN, HR, HEAD, MANAGER")
    public List<LeaveRequestResponse> getPendingRequests() {
        return leaveService.getPendingRequests();
    }

    @GetMapping("/pending/page")
    @PreAuthorize("hasAnyRole('ADMIN','HR','HEAD','MANAGER')")
    @Operation(summary = "List pending leave requests (paged)", description = "Allowed roles: ADMIN, HR, HEAD, MANAGER")
    public Page<LeaveRequestResponse> getPendingRequestsPage(
            @RequestParam(defaultValue = "0") @Min(0) Integer page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) Integer size
    ) {
        return leaveService.getPendingRequestsPage(page, size);
    }

    @GetMapping("/employee/{employeeId}")
    @Operation(summary = "List leave requests by employee", description = "Allowed roles: ADMIN, HR, EMPLOYEE")
    public List<LeaveRequestResponse> getEmployeeLeaveRequests(@PathVariable @Positive Long employeeId) {
        return leaveService.getEmployeeLeaveRequests(employeeId);
    }

    @GetMapping("/balance/employee/{employeeId}")
    @Operation(summary = "Get employee leave balances", description = "Allowed roles: ADMIN, HR, EMPLOYEE")
    public EmployeeLeaveBalanceResponse getEmployeeLeaveBalance(@PathVariable @Positive Long employeeId) {
        return leaveService.getEmployeeLeaveBalance(employeeId);
    }

    @GetMapping("/leave-types")
    @Operation(summary = "List all leave types", description = "Allowed roles: ADMIN, HR, EMPLOYEE")
    public List<Map<String, String>> getLeaveTypes() {
        return Arrays.stream(LeaveType.values())
                .map(type -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("code", type.name());
                    entry.put("name", type.getDisplayName());
                    return entry;
                })
                .collect(Collectors.toList());
    }

}
