package com.localgov.repository;

import com.localgov.domain.model.PayrollAdjustment;
import com.localgov.domain.model.PayrollAdjustmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface PayrollAdjustmentRepository extends JpaRepository<PayrollAdjustment, Long> {

    List<PayrollAdjustment> findByEmployeeIdAndStatus(String employeeId, PayrollAdjustmentStatus status);

    /**
     * Sum of PENDING EARNING adjustments for an employee — this is what
     * the engine adds to gross as a BACK_PAY line.
     */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM PayrollAdjustment a " +
           "WHERE a.employeeId = :employeeId " +
           "  AND a.status = :status " +
           "  AND a.direction = com.localgov.domain.model.PayrollAdjustmentDirection.EARNING")
    BigDecimal sumPendingEarningByEmployeeId(
            @Param("employeeId") String employeeId,
            @Param("status") PayrollAdjustmentStatus status);

    /**
     * Sum of PENDING DEDUCTION adjustments for an employee — used in
     * V138+ when emitting over-payment corrections. Returns 0 in V137
     * because only EARNINGs are auto-created; the field is here for
     * future symmetry.
     */
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM PayrollAdjustment a " +
           "WHERE a.employeeId = :employeeId " +
           "  AND a.status = :status " +
           "  AND a.direction = com.localgov.domain.model.PayrollAdjustmentDirection.DEDUCTION")
    BigDecimal sumPendingDeductionByEmployeeId(
            @Param("employeeId") String employeeId,
            @Param("status") PayrollAdjustmentStatus status);

    /**
     * All adjustments produced by a specific retroactive_salary_event.
     * Used by V138.3's detail endpoint to enumerate the per-employee
     * arrears this event generated.
     *
     * <p>Scoped by authority_code so even a misconfigured RLS fails
     * closed here — the controller additionally checks the event
     * belongs to the caller's tenant.
     */
    List<PayrollAdjustment> findByRetroactiveEventIdAndAuthorityCodeOrderByEmployeeIdAsc(
            Long retroactiveEventId, String authorityCode);

    /**
     * Fetch PENDING adjustments that apply to a specific payroll period.
     * 
     * EARNING adjustments (back-pay, arrears) apply regardless of period
     * because they are compensating for past periods and are paid now.
     * 
     * DEDUCTION adjustments (overpayment, surcharge) apply only if their
     * period_from/period_to window intersects the current payroll period,
     * allowing multi-installment recovery (e.g., K1,000/month for 5 months).
     */
    @Query("SELECT a FROM PayrollAdjustment a " +
           "WHERE a.employeeId = :employeeId " +
           "  AND a.status = :status " +
           "  AND (" +
           "    a.direction = com.localgov.domain.model.PayrollAdjustmentDirection.EARNING " +
           "    OR (a.periodFrom IS NULL AND a.periodTo IS NULL) " +
           "    OR (a.periodFrom <= :periodEnd AND a.periodTo >= :periodStart)" +
           "  )")
    List<PayrollAdjustment> findApplicableForPeriod(
            @Param("employeeId") String employeeId,
            @Param("status") PayrollAdjustmentStatus status,
            @Param("periodStart") java.time.LocalDate periodStart,
            @Param("periodEnd") java.time.LocalDate periodEnd);

}
