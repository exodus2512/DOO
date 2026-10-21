package com.doo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-site credential vault entry. Passwords are encrypted at rest using AES-256-GCM.
 * The encryption key is derived from an environment variable (VAULT_SECRET).
 */
@Entity
@Table(name = "credential_vault", uniqueConstraints = @UniqueConstraint(columnNames = "domain"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CredentialVaultEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "domain", nullable = false, length = 255)
    private String domain;

    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "encrypted_password", nullable = false, columnDefinition = "TEXT")
    private String encryptedPassword;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
