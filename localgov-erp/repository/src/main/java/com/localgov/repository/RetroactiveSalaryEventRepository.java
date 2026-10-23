package com.localgov.repository;

import com.localgov.domain.model.RetroactiveSalaryEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RetroactiveSalaryEventRepository extends JpaRepository<RetroactiveSalaryEvent, Long> {
    List<RetroactiveSalaryEvent> findByAuthorityCodeOrderByImportedAtDesc(String authorityCode);
    Optional<RetroactiveSalaryEvent> findByAuthorityCodeAndTemplateId(String authorityCode, Long templateId);
    List<RetroactiveSalaryEvent> findByAuthorityCodeAndStatus(String authorityCode, String status);
}
