package de.tum.cit.artemis.push.common;

import jakarta.validation.constraints.NotBlank;

/**
 * A single encrypted push notification to relay.
 *
 * <p>{@code apiType} is intentionally not validated: only the APNs relay uses it, so Android (Firebase) requests may
 * omit it.
 */
public record NotificationRequest(@NotBlank String initializationVector, @NotBlank String payloadCipherText, @NotBlank String token, PushNotificationApiType apiType) {
}
