package com.learning.payment_service.core.provider;

import com.learning.payment_service.core.domain.Provider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ProviderRegistry {

    private final Map<Provider, PaymentProvider> providers = new EnumMap<>(Provider.class);

    public ProviderRegistry(List<PaymentProvider> all) {
        all.forEach(p -> providers.put(p.id(), p));
    }

    public PaymentProvider get(Provider provider) {
        var found = providers.get(provider);
        if (found == null) {
            throw new IllegalStateException("No payment provider configured for " + provider);
        }
        return found;
    }
}
