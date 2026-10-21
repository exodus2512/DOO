package com.doo.repository;

import com.doo.domain.Task;
import com.doo.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByIdempotencyKey(String idempotencyKey);

    Page<Task> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Task> findByStatusIn(List<TaskStatus> statuses);

    /**
     * Mark stale RUNNING tasks as FAILED (recovery after crash/restart)
     */
    @Modifying
    @Query("UPDATE Task t SET t.status = 'FAILED', t.failureReason = 'Task timed out or worker crashed', " +
           "t.completedAt = :now, t.updatedAt = :now " +
           "WHERE t.status = 'RUNNING' AND t.startedAt < :staleThreshold")
    int markStaleTasksFailed(@Param("staleThreshold") Instant staleThreshold, @Param("now") Instant now);
}
