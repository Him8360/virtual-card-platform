package com.example.cards.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(@DefaultValue Card card, @DefaultValue RateLimit rateLimit) {

    /** expirationDays = 0 means cards never expire. */
    public record Card(@DefaultValue("365") int expirationDays) {}

    public record RateLimit(@DefaultValue("true") boolean enabled,
                            @DefaultValue("60") int capacity,
                            @DefaultValue("30") double refillPerSecond) {}
}
