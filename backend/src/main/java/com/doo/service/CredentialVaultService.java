package com.doo.service;

import com.doo.domain.CredentialVaultEntry;
import com.doo.repository.CredentialVaultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Per-site credential vault with AES-256-GCM encryption at rest.
 *
 * SECURITY CONTRACT:
 * - Passwords are encrypted before storage and decrypted only at action-fill time
 * - Passwords NEVER appear in logs, LLM prompts, or API responses (except the worker fill path)
 * - The list endpoint returns domains and usernames only, never passwords
 * - Vault access is logged for audit
 */
@Slf4j
@Service
public class CredentialVaultService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final CredentialVaultRepository repository;
    private final SecretKey secretKey;

    public CredentialVaultService(
        CredentialVaultRepository repository,
        @Value("${doo.vault.secret:default-vault-secret-change-in-production}") String vaultSecret
    ) {
        this.repository = repository;
        this.secretKey = deriveKey(vaultSecret);
    }

    public List<VaultDomainInfo> listDomains() {
        return repository.findAll().stream()
            .map(e -> new VaultDomainInfo(e.getDomain(), e.getUsername(), e.getCreatedAt().toString()))
            .toList();
    }

    public Optional<VaultDomainInfo> getDomainInfo(String domain) {
        return repository.findByDomain(domain.toLowerCase())
            .map(e -> new VaultDomainInfo(e.getDomain(), e.getUsername(), e.getCreatedAt().toString()));
    }

    @Transactional
    public void upsert(String domain, String username, String password) {
        domain = domain.toLowerCase();
        String encrypted = encrypt(password);
        log.info("Vault: upsert credentials for domain={}", domain);

        Optional<CredentialVaultEntry> existing = repository.findByDomain(domain);
        if (existing.isPresent()) {
            CredentialVaultEntry entry = existing.get();
            entry.setUsername(username);
            entry.setEncryptedPassword(encrypted);
            repository.save(entry);
        } else {
            CredentialVaultEntry entry = CredentialVaultEntry.builder()
                .domain(domain)
                .username(username)
                .encryptedPassword(encrypted)
                .build();
            repository.save(entry);
        }
    }

    @Transactional
    public boolean delete(String domain) {
        domain = domain.toLowerCase();
        Optional<CredentialVaultEntry> existing = repository.findByDomain(domain);
        if (existing.isPresent()) {
            repository.delete(existing.get());
            log.info("Vault: deleted credentials for domain={}", domain);
            return true;
        }
        return false;
    }

    /**
     * Resolve credentials for a domain. Returns username + decrypted password.
     * This method is ONLY called by the worker fill path — never by the LLM or API responses.
     */
    public Optional<ResolvedCredential> resolve(String domain) {
        final String normalizedDomain = domain.toLowerCase();
        return repository.findByDomain(normalizedDomain)
            .map(entry -> {
                String password = decrypt(entry.getEncryptedPassword());
                log.info("Vault: resolved credentials for domain={} (password redacted)", normalizedDomain);
                return new ResolvedCredential(entry.getUsername(), password);
            });
    }

    public boolean hasDomain(String domain) {
        return repository.findByDomain(domain.toLowerCase()).isPresent();
    }

    // ─── Encryption ──────────────────────────────────────────────────────────

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Vault encryption failed", e);
        }
    }

    private String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Vault decryption failed", e);
        }
    }

    private static SecretKey deriveKey(String secret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Could not derive vault key", e);
        }
    }

    // ─── Value objects ────────────────────────────────────────────────────────

    public record VaultDomainInfo(String domain, String username, String createdAt) {}
    public record ResolvedCredential(String username, String password) {}
}
