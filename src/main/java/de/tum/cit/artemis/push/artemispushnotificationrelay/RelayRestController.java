package de.tum.cit.artemis.push.artemispushnotificationrelay;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/push_notification")
public class RelayRestController {

    private FirebaseSendService firebaseSendService;
    @Autowired
    private ApnsSendService apnsSendService;

    @PostMapping("send_firebase")
    public ResponseEntity send(@RequestBody FirebaseSendPushNotificationsRequest notificationRequests) {
        return firebaseSendService.send(notificationRequests.notificationRequest());
    }

    @PostMapping("send_apns")
    public ResponseEntity send(@RequestBody NotificationRequest notificationRequest) {
        return apnsSendService.send(notificationRequest);
    }

    @GetMapping("alive")
    public ResponseEntity alive() {
        return ResponseEntity.ok().build();
    }
}
