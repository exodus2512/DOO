package com.doo.repository;

import com.doo.domain.TaskResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskResultRepository extends JpaRepository<TaskResult, UUID> {
    Optional<TaskResult> findByTaskId(UUID taskId);
}
