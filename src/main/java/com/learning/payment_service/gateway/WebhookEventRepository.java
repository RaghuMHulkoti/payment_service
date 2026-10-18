package com.learning.payment_service.gateway;

import com.learning.payment_service.gateway.entity.WebhookEventEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, UUID> {

    List<WebhookEventEntity> findByProcessedAtIsNullOrderByReceivedAt(Limit limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WebhookEventEntity e SET e.processedAt = :processedAt WHERE e.id = :id")
    int markProcessed(@Param("id") UUID id, @Param("processedAt") OffsetDateTime processedAt);
}
