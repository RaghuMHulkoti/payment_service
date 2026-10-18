package com.learning.payment_service.gateway;

import com.learning.payment_service.gateway.entity.PaymentAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, UUID> {

    @Query("SELECT COALESCE(MAX(a.attemptNo), 0) FROM PaymentAttemptEntity a WHERE a.intentId = :intentId")
    int maxAttemptNo(@Param("intentId") UUID intentId);
}
