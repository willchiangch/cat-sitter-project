package com.petsitter.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public class NotificationPreference extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String category;

    @Builder.Default
    @Column(name = "enable_in_app", nullable = false)
    private boolean enableInApp = true;

    @Builder.Default
    @Column(name = "enable_email", nullable = false)
    private boolean enableEmail = true;
}
