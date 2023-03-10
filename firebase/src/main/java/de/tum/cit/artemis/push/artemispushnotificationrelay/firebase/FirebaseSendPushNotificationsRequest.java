package de.tum.cit.artemis.push.artemispushnotificationrelay.firebase;

import de.tum.cit.artemis.push.artemispushnotificationrelay.common.NotificationRequest;

import java.util.List;

public record FirebaseSendPushNotificationsRequest(
    List<NotificationRequest> notificationRequest
) {
}
