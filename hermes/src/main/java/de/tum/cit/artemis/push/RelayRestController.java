package de.tum.cit.artemis.push;

import de.tum.cit.artemis.push.apns.ApnsSendService;
import de.tum.cit.artemis.push.common.NotificationRequest;
import de.tum.cit.artemis.push.firebase.FirebaseSendPushNotificationsRequest;
import de.tum.cit.artemis.push.firebase.FirebaseSendService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/api/push_notification")
public class RelayRestController {

    private final FirebaseSendService firebaseSendService;

    private final ApnsSendService apnsSendService;

    public RelayRestController(FirebaseSendService firebaseSendService, ApnsSendService apnsSendService) {
        this.firebaseSendService = firebaseSendService;
        this.apnsSendService = apnsSendService;
    }

    // Returns a DeferredResult so the Tomcat request thread is released immediately while the notification is
    // relayed on a bounded worker pool. The HTTP connection stays open until the send completes or the safety
    // timeout fires (then 503). This is what keeps a provider outage from starving the /api/health endpoint.
    @PostMapping("send_firebase")
    public DeferredResult<ResponseEntity<Void>> send(@RequestBody FirebaseSendPushNotificationsRequest notificationRequests) {
        return firebaseSendService.send(notificationRequests.notificationRequest());
    }

    @PostMapping("send_apns")
    public DeferredResult<ResponseEntity<Void>> send(@RequestBody NotificationRequest notificationRequest) {
        return apnsSendService.send(notificationRequest);
    }

    @GetMapping("alive")
    public ResponseEntity<Void> alive() {
        return ResponseEntity.ok().build();
    }
}
