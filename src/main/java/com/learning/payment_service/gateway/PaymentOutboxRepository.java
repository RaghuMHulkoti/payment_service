package com.learning.payment_service.gateway;

import com.learning.payment_service.gateway.entity.PaymentOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentOutboxRepository extends JpaRepository<PaymentOutboxEntity, Long> {

    /**
     * Locks a batch of pending rows; other pollers skip them, so replicas never double-send.
     * Oracle rejects FETCH FIRST together with FOR UPDATE, so the limit sits in a subquery.
     */
    @Query(value = "select * from {h-schema}payment_outbox where id in ("
            + "select id from {h-schema}payment_outbox where status = 'PENDING' order by id fetch first :limit rows only) "
            + "order by id for update skip locked", nativeQuery = true)
    List<PaymentOutboxEntity> lockPendingBatch(int limit);
}
