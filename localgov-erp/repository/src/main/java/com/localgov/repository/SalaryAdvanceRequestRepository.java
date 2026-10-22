package com.localgov.repository;

import com.localgov.domain.model.SalaryAdvanceRequestStatus;
import com.localgov.domain.model.SalaryAdvanceRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SalaryAdvanceRequestRepository extends JpaRepository<SalaryAdvanceRequest, Long> {
    @Query("SELECT r FROM SalaryAdvanceRequest r WHERE r.employee.employeeId = :employeeId ORDER BY r.createdAt DESC")
    List<SalaryAdvanceRequest> findByEmployeeIdOrderByCreatedAtDesc(@Param("employeeId") String employeeId);

    @Query("SELECT r FROM SalaryAdvanceRequest r WHERE r.employee.employeeId = :employeeId")
    Page<SalaryAdvanceRequest> findByEmployeeId(@Param("employeeId") String employeeId, Pageable pageable);

        @Query("""
            SELECT r FROM SalaryAdvanceRequest r
            WHERE (:status IS NULL OR r.status = :status)
            ORDER BY r.createdAt DESC
            """)
        Page<SalaryAdvanceRequest> findAllWithStatus(
            @Param("status") SalaryAdvanceRequestStatus status,
            Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN TRUE ELSE FALSE END FROM SalaryAdvanceRequest r WHERE r.employee.employeeId = :employeeId AND r.status IN :statuses")
    boolean existsByEmployeeIdAndStatusIn(@Param("employeeId") String employeeId, @Param("statuses") List<SalaryAdvanceRequestStatus> statuses);

    @Query("SELECT r FROM SalaryAdvanceRequest r WHERE r.employee.employeeId = :employeeId AND r.status IN :statuses ORDER BY r.createdAt DESC")
    List<SalaryAdvanceRequest> findByEmployeeIdAndStatusInOrderByCreatedAtDesc(
            @Param("employeeId") String employeeId,
            @Param("statuses") List<SalaryAdvanceRequestStatus> statuses);
}