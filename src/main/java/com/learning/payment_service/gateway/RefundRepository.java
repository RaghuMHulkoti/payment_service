package com.learning.payment_service.gateway;

import com.learning.payment_service.gateway.entity.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<RefundEntity, UUID> {

    Optional<RefundEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<RefundEntity> findByProviderRefundId(String providerRefundId);

    List<RefundEntity> findByIntentIdOrderByCreatedAt(UUID intentId);

    /** Null when the intent has no refund in that status. */
    @Query("SELECT SUM(r.amount) FROM RefundEntity r WHERE r.intentId = :intentId AND r.status <> 'FAILED'")
    BigDecimal sumNotFailed(@Param("intentId") UUID intentId);

    @Query("SELECT SUM(r.amount) FROM RefundEntity r WHERE r.intentId = :intentId AND r.status = 'SUCCESS'")
    BigDecimal sumSucceeded(@Param("intentId") UUID intentId);

    /** Compare-and-set on the version the caller read; 0 rows means someone else changed the refund first. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE RefundEntity r
            SET r.status = :status, r.providerRefundId = :providerRefundId, r.completedAt = :completedAt,
                r.version = r.version + 1
            WHERE r.id = :id AND r.version = :version""")
    int updateIfVersion(@Param("id") UUID id, @Param("version") long version, @Param("status") String status,
                        @Param("providerRefundId") String providerRefundId,
                        @Param("completedAt") OffsetDateTime completedAt);
}
