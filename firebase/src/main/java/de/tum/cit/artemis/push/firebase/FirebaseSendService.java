package de.tum.cit.artemis.push.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import de.tum.cit.artemis.push.common.NotificationRequest;
import de.tum.cit.artemis.push.common.SendService;
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

    private static final Logger log = LoggerFactory.getLogger(FirebaseSendService.class);

    private Optional<FirebaseApp> firebaseApp = Optional.empty();

    private boolean isConnected;

    public FirebaseSendService() {
        try {
            FirebaseOptions options = FirebaseOptions
                    .builder()
                    // Get credentials from GOOGLE_APPLICATION_CREDENTIALS env var.
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .build();

            firebaseApp = Optional.of(FirebaseApp.initializeApp(options));

            isConnected = true;
        } catch (IOException e) {
            log.error("Exception while loading Firebase credentials", e);
            isConnected = false;
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
                                    .putData("payload", request.payloadCipherText())
                                    .putData("iv", request.initializationVector())
                                    .setToken(request.token())
                                    .build()
                    )
                    .toList();

            try {
                FirebaseMessaging.getInstance(firebaseApp.get()).sendEach(batch);
                isConnected = true;
            } catch (FirebaseMessagingException e) {
                // In case the certificate is invalid, the THIRD_PARTY_AUTH_ERROR error code will be returned
                var errorCodes = List.of(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR);

                if(errorCodes.contains(e.getMessagingErrorCode())) {
                    isConnected = false;
                }

                return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
            }
        }

        return ResponseEntity.ok().build();
    }

    @Override
    public boolean isHealthy() {
        return isConnected;
    }
}
