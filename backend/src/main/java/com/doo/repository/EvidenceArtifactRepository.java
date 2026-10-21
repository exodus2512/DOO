package com.doo.repository;

import com.doo.domain.EvidenceArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EvidenceArtifactRepository extends JpaRepository<EvidenceArtifact, UUID> {
    List<EvidenceArtifact> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
