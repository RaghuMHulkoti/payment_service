package com.learning.payment_service.web;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.processor.ReceiveWebhookProcessor;
import com.learning.payment_service.core.processor.ReceiveWebhookProcessorRequest;
import com.learning.payment_service.core.provider.WebhookHandler.WebhookRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * PSP callbacks (TDD §14.4). They carry no user token; the signature is the authentication. Each handler only
 * verifies and stores, then answers 200 well inside the PSP's 5 s limit. The body is read as raw bytes because
 * the signature is computed over the exact bytes the PSP sent.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks")
public class WebhookController {

    private final ReceiveWebhookProcessor processor;

    public WebhookController(ReceiveWebhookProcessor processor) {
        this.processor = processor;
    }

    @PostMapping("/stripe")
    @Operation(summary = "Stripe webhook (Stripe-Signature)")
    public ResponseEntity<Void> stripe(@RequestHeader HttpHeaders headers, @RequestBody byte[] body) {
        return receive(Provider.STRIPE, headers, body);
    }

    @PostMapping("/razorpay")
    @Operation(summary = "Razorpay webhook (X-Razorpay-Signature)")
    public ResponseEntity<Void> razorpay(@RequestHeader HttpHeaders headers, @RequestBody byte[] body) {
        return receive(Provider.RAZORPAY, headers, body);
    }

    private ResponseEntity<Void> receive(Provider provider, HttpHeaders headers, byte[] body) {
        processor.process(new ReceiveWebhookProcessorRequest(provider,
                new WebhookRequest(new String(body, StandardCharsets.UTF_8), headers::getFirst)));
        return ResponseEntity.ok().build();
    }
}
