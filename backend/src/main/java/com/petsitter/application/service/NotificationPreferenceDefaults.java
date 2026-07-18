package com.petsitter.application.service;

import lombok.Value;
import java.util.Map;

public final class NotificationPreferenceDefaults {

    private NotificationPreferenceDefaults() {}

    public static final Map<String, PreferenceValue> DEFAULTS = Map.of(
        "ORDER_AFFAIR", new PreferenceValue(true, true),
        "ACCOUNT_AUTH", new PreferenceValue(true, true),
        "SUBSCRIPTION_MAINTENANCE", new PreferenceValue(true, false),
        "SERVICE_RECORD", new PreferenceValue(true, true),
        "REFERRAL", new PreferenceValue(true, true)
    );

    @Value
    public static class PreferenceValue {
        boolean enableInApp;
        boolean enableEmail;
    }
}
