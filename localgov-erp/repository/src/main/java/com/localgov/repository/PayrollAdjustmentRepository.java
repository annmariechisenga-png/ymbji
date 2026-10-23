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
}
