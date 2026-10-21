package com.doo.repository;

import com.doo.domain.CredentialVaultEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CredentialVaultRepository extends JpaRepository<CredentialVaultEntry, UUID> {
    Optional<CredentialVaultEntry> findByDomain(String domain);
    void deleteByDomain(String domain);
}
