package com.learning.payment_service.web.response;

import com.learning.payment_service.common.StatusCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> {
    private StatusCode status;
    private String message;
    private T body;
}
