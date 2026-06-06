package com.portfolio.orderservice.repository;

import com.portfolio.orderservice.model.SagaCompensationStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SagaCompensationStepRepository extends JpaRepository<SagaCompensationStep, UUID> {

    List<SagaCompensationStep> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    @Transactional
    @Modifying
    @Query("UPDATE SagaCompensationStep s SET s.retryCount = s.retryCount + 1, s.lastAttemptedAt = :now, s.status = 'RETRYING' WHERE s.id = :id")
    void recordAttempt(UUID id, Instant now);

    @Transactional
    @Modifying
    @Query("UPDATE SagaCompensationStep s SET s.status = 'COMPLETED' WHERE s.id = :id")
    void markCompleted(UUID id);

    @Transactional
    @Modifying
    @Query("UPDATE SagaCompensationStep s SET s.status = 'DEAD_LETTER', s.lastAttemptedAt = :now WHERE s.id = :id")
    void markDeadLetter(UUID id, Instant now);
}
