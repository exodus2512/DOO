package com.doo.api;

import com.doo.service.CredentialVaultService;
import com.doo.service.CredentialVaultService.VaultDomainInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for the per-site credential vault.
 * SECURITY: Passwords are NEVER returned in any response.
 */
@Slf4j
@RestController
@RequestMapping("/api/vault")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000"}, allowCredentials = "false")
public class VaultController {

    private final CredentialVaultService vaultService;

    @GetMapping
    public ResponseEntity<List<VaultDomainInfo>> listDomains() {
        return ResponseEntity.ok(vaultService.listDomains());
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> upsert(@Valid @RequestBody UpsertRequest req) {
        vaultService.upsert(req.domain(), req.username(), req.password());
        return ResponseEntity.ok(Map.of("status", "saved", "domain", req.domain()));
    }

    @DeleteMapping("/{domain}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String domain) {
        boolean deleted = vaultService.delete(domain);
        return ResponseEntity.ok(Map.of("deleted", deleted, "domain", domain));
    }

    public record UpsertRequest(
        @NotBlank String domain,
        @NotBlank String username,
        @NotBlank String password
    ) {}
}
