package com.doo.repository;

import com.doo.domain.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {
    List<ApprovalRequest> findByTaskIdAndStatus(UUID taskId, String status);
    Optional<ApprovalRequest> findFirstByTaskIdAndStatusOrderByCreatedAtDesc(UUID taskId, String status);
    Optional<ApprovalRequest> findFirstByTaskIdAndProposedActionAndStatus(UUID taskId, String proposedAction, String status);
}
