package com.localgov.repository;

import com.localgov.domain.model.FederationPayrollEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FederationPayrollEventRepository extends JpaRepository<FederationPayrollEvent, Long> {
    List<FederationPayrollEvent> findByIsActiveTrueOrderByEffectiveFromDesc();
    Optional<FederationPayrollEvent> findByEventCode(String eventCode);
}
