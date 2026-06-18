package de.tum.cit.artemis.push.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import de.tum.cit.artemis.push.common.BoundedSendExecutor;
import de.tum.cit.artemis.push.common.NotificationRequest;
import de.tum.cit.artemis.push.common.SendService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Relays push notifications to Firebase Cloud Messaging (FCM) for Android devices.
 *
 * <p>Like {@link de.tum.cit.artemis.push.apns.ApnsSendService}, batches are sent on a {@link BoundedSendExecutor}
 * (a fixed worker pool + bounded queue), never on the Tomcat request thread, so a slow or unreachable FCM cannot
 * exhaust the servlet thread pool and starve the health endpoint. Health is a cached flag updated honestly by send
 * outcomes; reading it never performs I/O.
 */
@Service
public class FirebaseSendService implements SendService<List<NotificationRequest>> {

    private static final Logger log = LoggerFactory.getLogger(FirebaseSendService.class);

    /** FCM's documented maximum number of messages per {@code sendEach} batch. */
    private static final int MAX_BATCH_SIZE = 500;

    /**
     * FCM error codes that indicate a per-device problem (a bad/unregistered token), as opposed to a provider-side
     * or credential problem. When an entire batch fails, the relay stays healthy ONLY if every failure carries one
     * of these codes; any other code (including a null/unknown code) is treated as a provider failure, so the
     * health check fails closed rather than masking an outage with unknown causes.
     */
    private static final Set<MessagingErrorCode> PER_DEVICE_ERROR_CODES = Set.of(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT);

    @Value("${firebase.workers:16}")
    private int workers;

    @Value("${firebase.queue-capacity:2000}")
    private int queueCapacity;

    @Value("${firebase.response-timeout-ms:30000}")
    private long responseTimeoutMs;

    private final Optional<FirebaseApp> firebaseApp;

    private volatile boolean isConnected;

    private BoundedSendExecutor dispatcher;

    public FirebaseSendService() {
        this(loadDefaultFirebaseApp());
    }

    // Visible for testing: allows injecting a FirebaseApp backed by a mock HTTP transport
    // so the full send path can be exercised against a faked FCM gateway.
    FirebaseSendService(Optional<FirebaseApp> firebaseApp) {
        this.firebaseApp = firebaseApp;
        this.isConnected = firebaseApp.isPresent();
    }

    @PostConstruct
    public void init() {
        dispatcher = new BoundedSendExecutor("firebase", workers, queueCapacity, responseTimeoutMs);
    }

    private static Optional<FirebaseApp> loadDefaultFirebaseApp() {
        try {
            FirebaseOptions options = FirebaseOptions
                    .builder()
                    // Get credentials from GOOGLE_APPLICATION_CREDENTIALS env var.
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .build();

            return Optional.of(FirebaseApp.initializeApp(options));
        } catch (IOException e) {
            log.error("Exception while loading Firebase credentials", e);
            return Optional.empty();
        }
    }

    @Override
    public DeferredResult<ResponseEntity<Void>> send(List<NotificationRequest> requests) {
        if (requests.size() > MAX_BATCH_SIZE) {
            return completed(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
        }
        // If the firebase app is not present, there is nothing to send; mirror the previous no-op success.
        if (firebaseApp.isEmpty()) {
            return completed(ResponseEntity.ok().build());
        }
        if (dispatcher == null) {
            // Should not happen once the bean is initialized, but stay defensive rather than NPE.
            return completed(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
        }
        return dispatcher.submit(() -> doSend(requests));
    }

    // visible for testing — performs the blocking batch send on a worker thread and maps the outcome to a response
    ResponseEntity<Void> doSend(List<NotificationRequest> requests) {
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

        if (batch.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        try {
            BatchResponse response = FirebaseMessaging.getInstance(firebaseApp.orElseThrow()).sendEach(batch);
            if (response.getSuccessCount() > 0) {
                // At least one message was accepted — FCM is reachable and our credentials work. Any remaining
                // failures in the batch are per-device (e.g. unregistered tokens) and do not make the relay unhealthy.
                isConnected = true;
                if (response.getFailureCount() > 0) {
                    log.warn("Firebase batch completed with {} failed message(s) out of {}", response.getFailureCount(), batch.size());
                }
                return ResponseEntity.ok().build();
            }
            // Nothing was accepted. Stay healthy only if EVERY failure is an explicit per-device problem (a bad
            // device token); otherwise — a provider/credential outage, or unknown/null error codes — fail closed
            // and report unhealthy + a retryable status, rather than masking an outage of unknown cause.
            if (allFailuresArePerDevice(response)) {
                isConnected = true;
                log.warn("All {} Firebase message(s) failed with per-device errors (e.g. invalid/unregistered tokens)", response.getFailureCount());
                return ResponseEntity.ok().build();
            }
            isConnected = false;
            log.error("All {} Firebase message(s) failed (provider/credential or unknown error); marking Firebase unhealthy", response.getFailureCount());
            return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
        } catch (FirebaseMessagingException e) {
            // A thrown exception means the batch could not be sent at all (authentication / transport) — provider down.
            isConnected = false;
            log.error("Failed to send push notifications via Firebase (provider unreachable): {}", e.getMessagingErrorCode(), e);
            return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
        }
    }

    /**
     * @return true if EVERY failed message in the batch carries an explicit per-device error code. A failure with a
     *         null/unknown code is not considered per-device, so this fails closed (treats it as a provider failure).
     */
    private static boolean allFailuresArePerDevice(BatchResponse response) {
        return response.getResponses().stream()
                .filter(sendResponse -> !sendResponse.isSuccessful())
                .allMatch(sendResponse -> {
                    FirebaseMessagingException exception = sendResponse.getException();
                    MessagingErrorCode code = exception != null ? exception.getMessagingErrorCode() : null;
                    return code != null && PER_DEVICE_ERROR_CODES.contains(code);
                });
    }

    @Override
    public boolean isHealthy() {
        return isConnected;
    }

    @PreDestroy
    public void shutdown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
        // FirebaseApp.initializeApp registers a global default app; delete it so resources are released and a
        // same-JVM restart does not fail with "FirebaseApp name [DEFAULT] already exists".
        firebaseApp.ifPresent(FirebaseApp::delete);
    }

    private static DeferredResult<ResponseEntity<Void>> completed(ResponseEntity<Void> response) {
        DeferredResult<ResponseEntity<Void>> deferred = new DeferredResult<>();
        deferred.setResult(response);
        return deferred;
    }
}
