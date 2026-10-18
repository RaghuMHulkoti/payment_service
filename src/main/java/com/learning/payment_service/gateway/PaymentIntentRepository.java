package com.learning.payment_service.gateway;

import com.learning.payment_service.gateway.entity.PaymentIntentEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentIntentRepository extends JpaRepository<PaymentIntentEntity, UUID> {

    Optional<PaymentIntentEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentIntentEntity> findByProviderAndProviderIntentId(String provider, String providerIntentId);

    List<PaymentIntentEntity> findByOrderIdOrderByCreatedAt(UUID orderId);

    List<PaymentIntentEntity> findByStatusInAndUpdatedAtBeforeOrderByUpdatedAt(Collection<String> statuses,
                                                                              OffsetDateTime olderThan, Limit limit);

    /** Compare-and-set on the version the caller read; 0 rows means someone else changed the intent first. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE PaymentIntentEntity i
            SET i.providerIntentId = :providerIntentId, i.status = :status, i.failureCode = :failureCode,
                i.failureMessage = :failureMessage, i.version = i.version + 1, i.updatedAt = :updatedAt
            WHERE i.id = :id AND i.version = :version""")
    int updateIfVersion(@Param("id") UUID id, @Param("version") long version,
                        @Param("providerIntentId") String providerIntentId, @Param("status") String status,
                        @Param("failureCode") String failureCode, @Param("failureMessage") String failureMessage,
                        @Param("updatedAt") OffsetDateTime updatedAt);
}
