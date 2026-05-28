package com.petsitter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "log_user_action")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "func_code", nullable = false, length = 100)
    private String funcCode;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType; // CREATE, UPDATE, DELETE

    @Builder.Default
    @Column(name = "action_result", nullable = false, length = 50)
    private String actionResult = "SUCCESS";

    @Column(name = "operator_id")
    private UUID operatorId;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "target_table", length = 100)
    private String targetTable;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
