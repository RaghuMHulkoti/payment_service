package com.learning.payment_service.core.service.refund.fail;

import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.core.service.ServiceRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailRefundServiceRequest implements ServiceRequest {
    private Refund refund;
}
