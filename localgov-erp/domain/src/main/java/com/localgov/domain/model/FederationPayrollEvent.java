package com.localgov.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Federation-level template for a retroactive payroll event.
 * No authority_code — shared across all 116 councils.
 *
 * <p>Created by superadmin (or migration). Each council imports the
 * template as a {@link RetroactiveSalaryEvent} in their own tenant.
 */
@Entity
@Table(name = "federation_payroll_event")
public class FederationPayrollEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_code", nullable = false, unique = true, length = 50)
    private String eventCode;

    @Column(name = "event_name", nullable = false, length = 200)
    private String eventName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "increase_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal increaseAmount;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "applies_to_scales", nullable = false, columnDefinition = "text[]")
    private String[] appliesToScales;

    @Column(name = "source_document", length = 500)
    private String sourceDocument;

    @Column(name = "source_date")
    private LocalDate sourceDate;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 120)
    private String createdBy = "SYSTEM";

    // ---- Accessors ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEventCode() { return eventCode; }
    public void setEventCode(String eventCode) { this.eventCode = eventCode; }

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }

    public BigDecimal getIncreaseAmount() { return increaseAmount; }
    public void setIncreaseAmount(BigDecimal increaseAmount) { this.increaseAmount = increaseAmount; }

    public String[] getAppliesToScales() { return appliesToScales; }
    public void setAppliesToScales(String[] appliesToScales) { this.appliesToScales = appliesToScales; }

    public List<String> getAppliesToScalesList() {
        return appliesToScales == null ? List.of() : Arrays.asList(appliesToScales);
    }

    public String getSourceDocument() { return sourceDocument; }
    public void setSourceDocument(String sourceDocument) { this.sourceDocument = sourceDocument; }

    public LocalDate getSourceDate() { return sourceDate; }
    public void setSourceDate(LocalDate sourceDate) { this.sourceDate = sourceDate; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
