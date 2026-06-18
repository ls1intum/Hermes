package de.tum.cit.artemis.push.apns;

import com.eatthepath.pushy.apns.*;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Relays push notifications to Apple Push Notification service (APNs) via the pushy library.
 *
 * <p>Sends run on a {@link BoundedSendExecutor} (a fixed worker pool + bounded queue), never on the Tomcat
 * request thread, so an unreachable APNs (most importantly: an expired client certificate) can never exhaust the
 * servlet thread pool and starve the health endpoint.
 *
 * <p>Health contract: the relay is healthy when it is <em>configured</em> (the certificate loaded successfully),
 * the loaded certificate is <em>not past its expiry</em>, and the most recent send (if any) did not reveal a
 * provider/credential problem. Reading health never performs I/O — the certificate expiry is captured once at
 * startup and compared in memory — so it cannot be starved by a provider outage.
 */
@Service
public class ApnsSendService implements SendService<NotificationRequest> {

    private static final Logger log = LoggerFactory.getLogger(ApnsSendService.class);

    /**
     * APNs reason strings (the wire values returned by {@link PushNotificationResponse#getRejectionReason()}) that
     * indicate OUR certificate/credentials are broken, i.e. every send will fail until it is fixed. Receiving one of
     * these — or, far more commonly, a TLS/connection exception — flips the relay to unhealthy. Every other rejection
     * reason (bad/unregistered device token, payload too large, rate limiting, ...) means APNs was reachable and
     * rejected this one notification, so those keep the relay healthy. The values are Apple's documented reason
     * phrases (which {@code getRejectionReason()} returns verbatim).
     */
    private static final Set<String> CREDENTIAL_REJECTION_REASONS = Set.of("BadCertificate", "BadCertificateEnvironment", "Forbidden", "ExpiredProviderToken",
            "InvalidProviderToken", "MissingProviderToken");

    @Value("${APNS_CERTIFICATE_PATH: #{null}}")
    private String apnsCertificatePath;

    @Value("${APNS_CERTIFICATE_PWD: #{null}}")
    private String apnsCertificatePwd;

    @Value("${APNS_PROD_ENVIRONMENT: #{false}}")
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

    private final ApnsCertificateInspector certificateInspector = new ApnsCertificateInspector();

    private ApnsClient apnsClient;

    private volatile boolean isConnected;

    /**
     * The earliest expiry of the certificate that the {@link ApnsClient} actually loaded, captured once at startup.
     * {@code null} if it could not be determined. Reading {@link #isHealthy()} compares this against the clock in
     * memory, so an expired certificate is reported immediately (no polling lag) and runtime file rotation cannot
     * mask the fact that pushy still holds the originally-loaded certificate.
     */
    private volatile Instant loadedCertificateExpiry;

    private BoundedSendExecutor dispatcher;

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        dispatcher = new BoundedSendExecutor("apns", workers, queueCapacity, responseTimeoutMs);

        if (apnsCertificatePwd == null || apnsCertificatePath == null || apnsProdEnvironment == null) {
            log.error("Could not init APNS service. Certificate information missing.");
            isConnected = false;
            return;
        }
        try {
            String apnsHost = apnsServerHost != null ? apnsServerHost
                    : (apnsProdEnvironment ? ApnsClientBuilder.PRODUCTION_APNS_HOST : ApnsClientBuilder.DEVELOPMENT_APNS_HOST);
            ApnsClientBuilder clientBuilder = new ApnsClientBuilder()
                    .setApnsServer(apnsHost, apnsServerPort)
                    .setClientCredentials(new File(apnsCertificatePath), apnsCertificatePwd)
                    .setConnectionTimeout(Duration.ofMillis(connectionTimeoutMs));
            if (apnsTrustedCertPath != null) {
                clientBuilder.setTrustedServerCertificateChain(new File(apnsTrustedCertPath));
            }
            apnsClient = clientBuilder.build();
            isConnected = true;
            log.info("Started APNS client successfully (environment: {})", apnsProdEnvironment ? "production" : "development");
        } catch (IOException e) {
            isConnected = false;
            log.error("Could not init APNS service", e);
        }

        // Report an already-expired certificate immediately, before the first send is ever attempted.
        captureLoadedCertificateExpiry();
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
            // The send never reached APNs — a TLS handshake / connection failure, which is exactly what an expired
            // client certificate produces. Treat as "provider unreachable" so health honestly reports the outage.
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
            // A successful send proves the connection (and thus the certificate) works.
            isConnected = true;
            log.info("Send notification to {}", token);
            return ResponseEntity.ok().build();
        }
        String reason = response.getRejectionReason().orElse("unknown");
        if (CREDENTIAL_REJECTION_REASONS.contains(reason)) {
            // Our certificate/credentials are broken — every send will fail until this is fixed.
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

    private void captureLoadedCertificateExpiry() {
        loadedCertificateExpiry = certificateInspector.earliestExpiry(apnsCertificatePath, apnsCertificatePwd).orElse(null);
        if (loadedCertificateExpiry == null) {
            log.warn("Could not determine the APNs certificate expiry; health will rely on send outcomes only");
        } else if (ApnsCertificateInspector.isExpired(loadedCertificateExpiry, Instant.now())) {
            log.error("APNs certificate is EXPIRED (expired at {}); APNs is reported unhealthy until a valid certificate is deployed", loadedCertificateExpiry);
        } else {
            log.info("APNs certificate is valid until {}", loadedCertificateExpiry);
        }
    }

    private boolean isLoadedCertificateExpired() {
        Instant expiry = loadedCertificateExpiry;
        return expiry != null && ApnsCertificateInspector.isExpired(expiry, Instant.now());
    }

    @Override
    public boolean isHealthy() {
        // ANDing with the certificate expiry means that even a previously-successful send (isConnected == true)
        // cannot make the relay report healthy once the loaded certificate is past its expiry. This is computed in
        // memory on every read, so it is honest at the exact expiry instant without any background polling.
        return isConnected && !isLoadedCertificateExpired();
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
