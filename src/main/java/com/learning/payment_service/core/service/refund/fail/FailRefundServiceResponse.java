package com.learning.payment_service.core.service.refund.fail;

import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.core.service.ServiceResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailRefundServiceResponse implements ServiceResponse {
    private Refund refund;
}
