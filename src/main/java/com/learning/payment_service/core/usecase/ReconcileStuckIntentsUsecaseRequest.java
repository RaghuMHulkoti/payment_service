package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.usecase.UsecaseRequest;
import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconcileStuckIntentsUsecaseRequest implements UsecaseRequest {
    private Duration stuckAfter;
    private int limit;
}
