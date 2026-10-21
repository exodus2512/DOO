package com.doo.repository;

import com.doo.domain.TaskStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskStepRepository extends JpaRepository<TaskStep, UUID> {
    List<TaskStep> findByTaskIdOrderByStepIndexAsc(UUID taskId);
}
