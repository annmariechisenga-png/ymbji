package com.localgov.repository;

import com.localgov.domain.model.SalaryAdvanceDeduction;
import com.localgov.domain.model.SalaryAdvanceDeductionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SalaryAdvanceDeductionRepository extends JpaRepository<SalaryAdvanceDeduction, Long> {

    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN TRUE ELSE FALSE END FROM SalaryAdvanceDeduction d "
         + "WHERE d.employee.employeeId = :employeeId AND d.status = :status")
    boolean existsByEmployee_IdAndStatus(
            @Param("employeeId") String employeeId,
            @Param("status") SalaryAdvanceDeductionStatus status);

    @Query("SELECT d FROM SalaryAdvanceDeduction d "
         + "WHERE d.employee.employeeId = :employeeId AND d.status = :status "
         + "AND d.scheduledPayPeriod <= :scheduledPayPeriod "
         + "ORDER BY d.scheduledPayPeriod ASC")
    List<SalaryAdvanceDeduction> findByEmployee_IdAndStatusAndScheduledPayPeriodLessThanEqualOrderByScheduledPayPeriodAsc(
            @Param("employeeId") String employeeId,
            @Param("status") SalaryAdvanceDeductionStatus status,
            @Param("scheduledPayPeriod") LocalDate scheduledPayPeriod);

    List<SalaryAdvanceDeduction> findByStatusAndScheduledPayPeriodLessThanEqual(
            SalaryAdvanceDeductionStatus status,
            LocalDate scheduledPayPeriod);

    @Query("SELECT d FROM SalaryAdvanceDeduction d "
         + "WHERE d.status = :status AND d.scheduledPayPeriod <= :scheduledPayPeriod "
         + "AND d.employee.employeeId = :employeeId")
    List<SalaryAdvanceDeduction> findByStatusAndScheduledPayPeriodLessThanEqualAndEmployee_Id(
            @Param("status") SalaryAdvanceDeductionStatus status,
            @Param("scheduledPayPeriod") LocalDate scheduledPayPeriod,
            @Param("employeeId") String employeeId);

    @Query("SELECT d FROM SalaryAdvanceDeduction d "
         + "WHERE d.scheduledPayPeriod <= :scheduledPayPeriod "
         + "AND d.employee.employeeId = :employeeId")
    List<SalaryAdvanceDeduction> findByScheduledPayPeriodLessThanEqualAndEmployee_Id(
            @Param("scheduledPayPeriod") LocalDate scheduledPayPeriod,
            @Param("employeeId") String employeeId);

    List<SalaryAdvanceDeduction> findByScheduledPayPeriodLessThanEqual(LocalDate scheduledPayPeriod);

    long countBySalaryAdvanceRequest_Id(Long salaryAdvanceRequestId);

    long countBySalaryAdvanceRequest_IdAndStatus(Long salaryAdvanceRequestId, SalaryAdvanceDeductionStatus status);

    @Query("SELECT COALESCE(SUM(d.deductionAmount), 0) FROM SalaryAdvanceDeduction d "
         + "WHERE d.employee.employeeId = :employeeId AND d.status = :status")
    java.math.BigDecimal sumDeductionAmountByEmployeeIdAndStatus(
            @Param("employeeId") String employeeId,
            @Param("status") SalaryAdvanceDeductionStatus status);

    @Query("SELECT d FROM SalaryAdvanceDeduction d "
         + "WHERE d.employee.employeeId = :employeeId AND d.status = :status "
         + "ORDER BY d.scheduledPayPeriod ASC")
    java.util.List<SalaryAdvanceDeduction> findByEmployeeIdAndStatusOrderByScheduledPayPeriodAsc(
            @Param("employeeId") String employeeId,
            @Param("status") SalaryAdvanceDeductionStatus status);
}
