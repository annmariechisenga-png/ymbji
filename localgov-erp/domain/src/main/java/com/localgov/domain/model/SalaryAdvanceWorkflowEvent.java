package com.localgov.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "salary_advance_workflow_event")
public class SalaryAdvanceWorkflowEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "salary_advance_request_id", nullable = false)
    private SalaryAdvanceRequest salaryAdvanceRequest;

    @Column(name = "event_stage", nullable = false, length = 40)
    private String eventStage;

    @Column(name = "event_action", nullable = false, length = 40)
    private String eventAction;

    @Column(name = "actor_role", nullable = false, length = 40)
    private String actorRole;

    @Column(name = "actor_name", length = 120)
    private String actorName;

    @Column(name = "authority_code", length = 50)
    private String authorityCode;

    @Column(name = "event_notes", length = 500)
    private String eventNotes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public SalaryAdvanceRequest getSalaryAdvanceRequest() {
        return salaryAdvanceRequest;
    }

    public void setSalaryAdvanceRequest(SalaryAdvanceRequest salaryAdvanceRequest) {
        this.salaryAdvanceRequest = salaryAdvanceRequest;
    }

    public String getEventStage() {
        return eventStage;
    }

    public void setEventStage(String eventStage) {
        this.eventStage = eventStage;
    }

    public String getEventAction() {
        return eventAction;
    }

    public void setEventAction(String eventAction) {
        this.eventAction = eventAction;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public String getEventNotes() {
        return eventNotes;
    }

    public void setEventNotes(String eventNotes) {
        this.eventNotes = eventNotes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getAuthorityCode() {
        return authorityCode;
    }

    public void setAuthorityCode(String authorityCode) {
        this.authorityCode = authorityCode;
    }
}