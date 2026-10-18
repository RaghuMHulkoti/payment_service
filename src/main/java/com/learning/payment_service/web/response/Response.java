package com.learning.payment_service.web.response;

import com.learning.payment_service.common.StatusCode;

public record Response<T>(StatusCode status, String message, T body) {
}
