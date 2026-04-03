package com.catsitter.api.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.catsitter.api.entity.common.AuditableEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Entity
@Table(name = "sitter_client_whitelists", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"sitter_profile_id", "client_profile_id"})
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class SitterClientWhitelist extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sitter_profile_id", nullable = false)
    private Profile sitterProfile;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_profile_id", nullable = false)
    private Profile clientProfile;

    @Column(name = "skip_questionnaire", nullable = false)
    private Boolean skipQuestionnaire = false;

    @Column(columnDefinition = "TEXT")
    private String notes;

    public void setId(UUID id) { this.id = id; }
    public UUID getId() { return id; }
    public Profile getSitterProfile() { return sitterProfile; }
    public void setSitterProfile(Profile sitterProfile) { this.sitterProfile = sitterProfile; }
    public Profile getClientProfile() { return clientProfile; }
    public void setClientProfile(Profile clientProfile) { this.clientProfile = clientProfile; }
    public Boolean getSkipQuestionnaire() { return skipQuestionnaire; }
    public void setSkipQuestionnaire(Boolean skipQuestionnaire) { this.skipQuestionnaire = skipQuestionnaire; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
