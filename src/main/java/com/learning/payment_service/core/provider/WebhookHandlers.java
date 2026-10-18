package com.learning.payment_service.core.provider;

import com.learning.payment_service.core.domain.Provider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class WebhookHandlers {

    private final Map<Provider, WebhookHandler> handlers = new EnumMap<>(Provider.class);

    public WebhookHandlers(List<WebhookHandler> all) {
        all.forEach(h -> handlers.put(h.provider(), h));
    }

    public WebhookHandler get(Provider provider) {
        var found = handlers.get(provider);
        if (found == null) {
            throw new IllegalStateException("No webhook handler configured for " + provider);
        }
        return found;
    }
}
