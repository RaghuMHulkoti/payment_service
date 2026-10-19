package com.learning.payment_service.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps this service inside the free cluster quota (10 topics, 64 partitions across all services). */
class EventTopicBudgetTest {

    @Test
    void createsOnlyTheTopicsItOwnsWithSmallPartitionCounts() {
        var created = AppConfig.ownedTopics("domain.payment.events", 3);
        assertThat(created).extracting(t -> t.name()).containsExactlyInAnyOrder("domain.payment.events");
        assertThat(created.stream().mapToInt(t -> t.numPartitions()).sum()).isEqualTo(3);
    }
}
