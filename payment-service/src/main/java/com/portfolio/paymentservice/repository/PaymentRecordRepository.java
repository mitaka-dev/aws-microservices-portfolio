package com.portfolio.paymentservice.repository;

import com.portfolio.paymentservice.model.PaymentRecord;
import com.portfolio.paymentservice.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, UUID> {

    @Transactional
    @Modifying
    @Query("UPDATE PaymentRecord r SET r.status = :status WHERE r.id = :id")
    void updateStatus(UUID id, PaymentStatus status);
}
