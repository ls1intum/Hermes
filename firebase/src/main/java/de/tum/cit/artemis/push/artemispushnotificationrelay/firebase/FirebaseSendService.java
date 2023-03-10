package de.tum.cit.artemis.push.artemispushnotificationrelay.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import de.tum.cit.artemis.push.artemispushnotificationrelay.common.NotificationRequest;
import de.tum.cit.artemis.push.artemispushnotificationrelay.common.SendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class FirebaseSendService implements SendService<List<NotificationRequest>> {

    private Optional<FirebaseApp> firebaseApp = Optional.empty();

    private final Logger log = LoggerFactory.getLogger(FirebaseSendService.class);

    public FirebaseSendService() {
        try {
            FirebaseOptions options = FirebaseOptions
                    .builder()
                    // Get credentials from GOOGLE_APPLICATION_CREDENTIALS env var.
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .build();

            firebaseApp = Optional.of(FirebaseApp.initializeApp(options));
        } catch (IOException e) {
            log.error("Exception while loading Firebase credentials", e);
        }
    }

    @Override
    public ResponseEntity<Void> send(List<NotificationRequest> requests) {
        if (requests.size() > 500) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // If the firebase app is not present, we do not have to do anything
        if (firebaseApp.isPresent()) {
            List<Message> batch = requests
                    .stream()
                    .map((request) ->
                            Message
                                    .builder()
                                    .putData("payload", request.getPayloadCipherText())
                                    .putData("iv", request.getInitializationVector())
                                    .setToken(request.getToken())
                                    .build()
                    )
                    .toList();

            try {
                FirebaseMessaging.getInstance(firebaseApp.get()).sendAll(batch);
            } catch (FirebaseMessagingException e) {
                return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
            }
        }

        return ResponseEntity.ok().build();
    }
}
