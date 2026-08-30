package de.tum.cit.artemis.push.apns;

import com.eatthepath.pushy.apns.*;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import de.tum.cit.artemis.push.common.BoundedSendExecutor;
import de.tum.cit.artemis.push.common.NotificationRequest;
import de.tum.cit.artemis.push.common.PushNotificationApiType;
import de.tum.cit.artemis.push.common.SendService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.File;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Relays push notifications to Apple Push Notification service (APNs) via the pushy library.
 *
 * <p>Authenticates with a token-based (JWT) connection: pushy signs each request with the APNs ES256 signing key
 * (the {@code .p8} identified by a Team ID and Key ID) and refreshes the token automatically. There is no client
 * certificate and therefore no certificate expiry to track.
 *
 * <p>Sends run on a {@link BoundedSendExecutor} (a fixed worker pool + bounded queue), never on the Tomcat
 * request thread, so an unreachable or misconfigured APNs can never exhaust the servlet thread pool and starve the
 * health endpoint.
 *
 * <p>Health contract: the relay is healthy when it is <em>configured</em> (the signing key loaded successfully at
 * startup) and the most recent send (if any) did not reveal a provider/credential problem. Reading health never
 * performs I/O — it returns a cached flag — so it cannot be starved by a provider outage.
 */
@Service
public class ApnsSendService implements SendService<NotificationRequest> {

    private static final Logger log = LoggerFactory.getLogger(ApnsSendService.class);

    /**
     * APNs reason strings (the wire values returned by {@link PushNotificationResponse#getRejectionReason()}) that
     * indicate OUR signing key/credentials are broken, i.e. every send will fail until it is fixed. Receiving one of
     * these — or, far more commonly, a TLS/connection exception — flips the relay to unhealthy. Every other rejection
     * reason (bad/unregistered device token, payload too large, rate limiting, ...) means APNs was reachable and
     * rejected this one notification, so those keep the relay healthy. The values are Apple's documented reason
     * phrases (which {@code getRejectionReason()} returns verbatim).
     *
     * <p>Notably absent is {@code ExpiredProviderToken}: it is <em>not</em> a broken-credential signal. Pushy
     * invalidates and regenerates its JWT when APNs returns it, so the next send re-authenticates and succeeds. A
     * genuinely wrong Team ID / Key ID / key surfaces as {@code InvalidProviderToken} instead, which is retained.
     */
    private static final Set<String> CREDENTIAL_REJECTION_REASONS = Set.of("Forbidden", "InvalidProviderToken", "MissingProviderToken");

    @Value("${APNS_TOKEN_KEY_PATH:#{null}}")
    private String apnsTokenKeyPath;

    @Value("${APNS_TEAM_ID:#{null}}")
    private String apnsTeamId;

    @Value("${APNS_KEY_ID:#{null}}")
    private String apnsKeyId;

    @Value("${APNS_PROD_ENVIRONMENT:#{false}}")
    private Boolean apnsProdEnvironment = false;

    // Optional overrides used to point the client at a mock APNS gateway in tests.
    // In production these stay unset and the real Apple hosts (port 443) are used.
    @Value("${APNS_SERVER_HOST:#{null}}")
    private String apnsServerHost;

    @Value("${APNS_SERVER_PORT:443}")
    private int apnsServerPort;

    @Value("${APNS_TRUSTED_CERT_PATH:#{null}}")
    private String apnsTrustedCertPath;

    @Value("${apns.workers:50}")
    private int workers;

    @Value("${apns.queue-capacity:2000}")
    private int queueCapacity;

    @Value("${apns.response-timeout-ms:30000}")
    private long responseTimeoutMs;

    @Value("${apns.connection-timeout-ms:2000}")
    private long connectionTimeoutMs;

    private ApnsClient apnsClient;

    private volatile boolean isConnected;

    private BoundedSendExecutor dispatcher;

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        dispatcher = new BoundedSendExecutor("apns", workers, queueCapacity, responseTimeoutMs);

        if (apnsTokenKeyPath == null || apnsTeamId == null || apnsKeyId == null || apnsProdEnvironment == null) {
            log.error("Could not init APNS service. Signing key information missing.");
            isConnected = false;
            return;
        }
        try {
            String apnsHost = apnsServerHost != null ? apnsServerHost
                    : (apnsProdEnvironment ? ApnsClientBuilder.PRODUCTION_APNS_HOST : ApnsClientBuilder.DEVELOPMENT_APNS_HOST);
            ApnsSigningKey signingKey = ApnsSigningKey.loadFromPkcs8File(new File(apnsTokenKeyPath), apnsTeamId, apnsKeyId);
            ApnsClientBuilder clientBuilder = new ApnsClientBuilder()
                    .setApnsServer(apnsHost, apnsServerPort)
                    .setSigningKey(signingKey)
                    .setConnectionTimeout(Duration.ofMillis(connectionTimeoutMs));
            if (apnsTrustedCertPath != null) {
                clientBuilder.setTrustedServerCertificateChain(new File(apnsTrustedCertPath));
            }
            apnsClient = clientBuilder.build();
            isConnected = true;
            log.info("Started APNS client successfully (environment: {})", apnsProdEnvironment ? "production" : "development");
        } catch (IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            // A missing, unreadable, or malformed .p8 signing key. This is the only pre-send credential validation
            // we do, so leave the relay unhealthy rather than discovering the problem on the first user notification.
            isConnected = false;
            log.error("Could not init APNS service", e);
        }
    }

    @Override
    public DeferredResult<ResponseEntity<Void>> send(NotificationRequest request) {
        if (dispatcher == null) {
            // Should not happen once the application is ready, but stay defensive rather than NPE.
            return completed(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
        }
        return dispatcher.submit(() -> doSend(request));
    }

    // visible for testing — performs the blocking send on a worker thread and maps the outcome to an HTTP response
    ResponseEntity<Void> doSend(NotificationRequest request) {
        if (apnsClient == null) {
            isConnected = false;
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        SimpleApnsPushNotification notification = buildNotification(request);

        PushNotificationFuture<SimpleApnsPushNotification, PushNotificationResponse<SimpleApnsPushNotification>> responseFuture = apnsClient.sendNotification(notification);
        try {
            PushNotificationResponse<SimpleApnsPushNotification> response = responseFuture.get();
            return handleResponse(response, request.token());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            isConnected = false;
            log.error("Interrupted while sending push notification.", e);
            return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
        } catch (ExecutionException e) {
            // The send never reached APNs — a TLS handshake / connection failure. Treat as "provider unreachable"
            // so health honestly reports the outage.
            isConnected = false;
            log.error("Failed to send push notification (provider unreachable).", e);
            return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
        }
    }

    private SimpleApnsPushNotification buildNotification(NotificationRequest request) {
        var payload = new SimpleApnsPayloadBuilder()
                .addCustomProperty("iv", request.initializationVector())
                .addCustomProperty("payload", request.payloadCipherText());

        var isV2Api = request.apiType() == PushNotificationApiType.IOS_V2;

        if (isV2Api) {
            payload.setMutableContent(true);
            // Alert Body is a fallback in case we cannot decrypt the payload
            payload.setAlertBody("There is a new notification in Artemis.");
        } else {
            payload.setContentAvailable(true);
        }

        return new SimpleApnsPushNotification(request.token(),
                "de.tum.cit.ase.artemis",
                payload.build(),
                Instant.now().plus(Duration.ofDays(7)),
                DeliveryPriority.getFromCode(5),
                isV2Api ? PushType.ALERT : PushType.BACKGROUND);
    }

    private ResponseEntity<Void> handleResponse(PushNotificationResponse<SimpleApnsPushNotification> response, String token) {
        if (response.isAccepted()) {
            // A successful send proves the connection (and thus the signing key) works.
            isConnected = true;
            log.info("Send notification to {}", token);
            return ResponseEntity.ok().build();
        }
        String reason = response.getRejectionReason().orElse("unknown");
        if (CREDENTIAL_REJECTION_REASONS.contains(reason)) {
            // Our signing key/credentials are broken — every send will fail until this is fixed.
            isConnected = false;
            log.error("Notification rejected by the APNs gateway due to a credential/configuration problem: {}", reason);
        } else {
            // APNs was reachable and rejected this single notification (e.g. bad/unregistered token). Stay healthy.
            isConnected = true;
            log.warn("Notification rejected by the APNs gateway for token {}: {}", token, reason);
        }
        response.getTokenInvalidationTimestamp().ifPresent(timestamp -> log.warn("\t... and the token is invalid as of {}", timestamp));
        return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
    }

    @Override
    public boolean isHealthy() {
        // Cached flag only: true once the signing key loaded at startup and no send has since revealed a
        // credential/connection problem. Read in memory on every call, so a provider outage cannot starve it.
        return isConnected;
    }

    @PreDestroy
    public void shutdown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
        if (apnsClient != null) {
            apnsClient.close();
        }
    }

    private static DeferredResult<ResponseEntity<Void>> completed(ResponseEntity<Void> response) {
        DeferredResult<ResponseEntity<Void>> deferred = new DeferredResult<>();
        deferred.setResult(response);
        return deferred;
    }
}
