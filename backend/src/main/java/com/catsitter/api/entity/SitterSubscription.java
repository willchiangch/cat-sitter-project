package com.catsitter.api.entity;

import com.catsitter.api.entity.common.AuditableEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sitter_subscriptions")
public class SitterSubscription extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne
    @JoinColumn(name = "sitter_profile_id", nullable = false)
    private Profile sitterProfile;

    @ManyToOne
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, length = 50)
    private String status; // ACTIVE, EXPIRED, CANCELLED

    public UUID getId() { return this.id; }
    public void setId(UUID id) { this.id = id; }
    public Profile getSitterProfile() { return this.sitterProfile; }
    public void setSitterProfile(Profile sitterProfile) { this.sitterProfile = sitterProfile; }
    public SubscriptionPlan getPlan() { return this.plan; }
    public void setPlan(SubscriptionPlan plan) { this.plan = plan; }
    public LocalDate getStartDate() { return this.startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return this.endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getStatus() { return this.status; }
    public void setStatus(String status) { this.status = status; }
}
