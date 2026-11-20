package de.tum.cit.artemis.push.firebase;

import de.tum.cit.artemis.push.common.NotificationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record FirebaseSendPushNotificationsRequest(@NotNull List<@NotNull @Valid NotificationRequest> notificationRequest) {
}
