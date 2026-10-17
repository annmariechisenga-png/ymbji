package com.localgov.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "erp_user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "employee_id", nullable = false, unique = true, length = 50)
    private String employeeId;

    @Column(name = "roles_csv", nullable = false, length = 200)
    private String rolesCsv;

    @Column(name = "dashboard_position_id", nullable = false, length = 80)
    private String dashboardPositionId;

    @Column(name = "authority_code", length = 50)
    private String authorityCode;

    @Column(name = "authority_type", nullable = false, length = 50)
    private String authorityType;

    @Column(name = "mfa_enabled", nullable = false)
    private Boolean mfaEnabled;

    @Column(name = "mfa_secret", length = 120)
    private String mfaSecret;

    @Column(name = "is_active", nullable = false)
    private Boolean active;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "privacy_consent_at")
    private LocalDateTime privacyConsentAt;

    @Column(name = "privacy_notice_version", nullable = false, length = 20)
    private String privacyNoticeVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (active == null) {
            active = true;
        }
        if (mfaEnabled == null) {
            mfaEnabled = false;
        }
        if (privacyNoticeVersion == null || privacyNoticeVersion.isBlank()) {
            privacyNoticeVersion = "2026.04";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
        if (active == null) {
            active = true;
        }
        if (mfaEnabled == null) {
            mfaEnabled = false;
        }
        if (privacyNoticeVersion == null || privacyNoticeVersion.isBlank()) {
            privacyNoticeVersion = "2026.04";
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getRolesCsv() {
        return rolesCsv;
    }

    public void setRolesCsv(String rolesCsv) {
        this.rolesCsv = rolesCsv;
    }

    public String getDashboardPositionId() {
        return dashboardPositionId;
    }

    public void setDashboardPositionId(String dashboardPositionId) {
        this.dashboardPositionId = dashboardPositionId;
    }

    public String getAuthorityCode() {
        return authorityCode;
    }

    public void setAuthorityCode(String authorityCode) {
        this.authorityCode = authorityCode;
    }

    public String getAuthorityType() {
        return authorityType;
    }

    public void setAuthorityType(String authorityType) {
        this.authorityType = authorityType;
    }

    public Boolean getMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(Boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public String getMfaSecret() {
        return mfaSecret;
    }

    public void setMfaSecret(String mfaSecret) {
        this.mfaSecret = mfaSecret;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public LocalDateTime getPrivacyConsentAt() {
        return privacyConsentAt;
    }

    public void setPrivacyConsentAt(LocalDateTime privacyConsentAt) {
        this.privacyConsentAt = privacyConsentAt;
    }

    public String getPrivacyNoticeVersion() {
        return privacyNoticeVersion;
    }

    public void setPrivacyNoticeVersion(String privacyNoticeVersion) {
        this.privacyNoticeVersion = privacyNoticeVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
