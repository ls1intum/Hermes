package de.tum.cit.artemis.push.common;

import jakarta.validation.constraints.NotEmpty;

/**
 * A single encrypted push notification to relay.
 *
 * <p>{@code apiType} is intentionally not validated: only the APNs relay uses it, and it is ignored for Firebase.
 */
public record NotificationRequest(@NotEmpty String initializationVector, @NotEmpty String payloadCipherText, @NotEmpty String token, PushNotificationApiType apiType) {
}
