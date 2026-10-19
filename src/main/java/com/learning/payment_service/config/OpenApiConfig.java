package com.learning.payment_service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShopTrail Payment Service API")
                        .description("Payment intents, attempts, refunds and PSP webhook reconciliation. Payments live in Oracle "
                                + "(schema PAYMENT). Card data is never stored: it is tokenised at the PSP. Every mutation takes an "
                                + "Idempotency-Key. Webhooks are authenticated by signature, not by user.")
                        .version("1.0.0"));
    }
}
