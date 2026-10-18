package com.learning.payment_service.gateway;

import com.learning.payment_service.gateway.entity.PaymentOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentOutboxRepository extends JpaRepository<PaymentOutboxEntity, Long> {
}
