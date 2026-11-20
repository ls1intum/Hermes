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
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    // APNs answers this when a token is sent to the gateway of the wrong environment (sandbox token on production or
    // the other way round) or is otherwise malformed. Only this reason triggers the fallback: Unregistered and
    // DeviceTokenNotForTopic mean the token is known to this environment, so retrying elsewhere cannot help.
    private static final String BAD_DEVICE_TOKEN = "BadDeviceToken";

    private static final String DEFAULT_TOPIC = "de.tum.cit.ase.artemis";

    private static final int DEFAULT_EXPIRATION_DAYS = 7;

    private static final int DEFAULT_ALERT_PRIORITY = 5;

    @Value("${APNS_TOKEN_KEY_PATH:#{null}}")
    private String apnsTokenKeyPath;

    @Value("${APNS_TEAM_ID:#{null}}")
    private String apnsTeamId;

    @Value("${APNS_KEY_ID:#{null}}")
    private String apnsKeyId;

    @Value("${APNS_PROD_ENVIRONMENT:#{false}}")
    private Boolean apnsProdEnvironment = false;

    /**
     * Device tokens are only valid on the APNs gateway that matches the app's signing environment: TestFlight and App
     * Store builds register production tokens, Xcode debug builds register sandbox tokens. When enabled, a send that
     * the primary gateway rejects as {@code BadDeviceToken} is retried once on the other gateway, so one relay serves
     * both kinds of build. The signing key must be valid for both environments.
     */
    @Value("${APNS_FALLBACK_ENVIRONMENT_ENABLED:#{false}}")
    private Boolean apnsFallbackEnabled = false;

    // Optional overrides used to point the clients at mock APNS gateways in tests.
    // In production these stay unset and the real Apple hosts (port 443) are used.
    @Value("${APNS_SERVER_HOST:#{null}}")
    private String apnsServerHost;

    @Value("${APNS_SERVER_PORT:443}")
    private int apnsServerPort;

    @Value("${APNS_FALLBACK_SERVER_HOST:#{null}}")
    private String apnsFallbackServerHost;

    @Value("${APNS_FALLBACK_SERVER_PORT:443}")
    private int apnsFallbackServerPort;

    @Value("${APNS_TRUSTED_CERT_PATH:#{null}}")
    private String apnsTrustedCertPath;

    // The app's bundle identifier; APNs rejects notifications whose topic does not match the signing key's app.
    @Value("${apns.topic:de.tum.cit.ase.artemis}")
    private String topic = DEFAULT_TOPIC;

    // How long APNs keeps trying to deliver a notification to an offline device.
    @Value("${apns.expiration-days:7}")
    private Integer expirationDays = DEFAULT_EXPIRATION_DAYS;

    // Delivery priority of visible (alert) notifications: 10 = immediately, 5 = power-considerate (may be delayed).
    // Background notifications must always use 5.
    @Value("${apns.alert-priority:5}")
    private Integer alertPriority = DEFAULT_ALERT_PRIORITY;

    @Value("${apns.workers:50}")
    private int workers;

    @Value("${apns.queue-capacity:2000}")
    private int queueCapacity;

    @Value("${apns.response-timeout-ms:30000}")
    private long responseTimeoutMs;

    @Value("${apns.connection-timeout-ms:2000}")
    private long connectionTimeoutMs;

    private ApnsClient apnsClient;

    // The gateway of the other environment; null unless the fallback is enabled and its client could be built.
    // It never influences health: only the primary gateway decides whether the relay is healthy.
    private ApnsClient fallbackApnsClient;

    private volatile boolean isConnected;

    private BoundedSendExecutor dispatcher;

    private MeterRegistry meterRegistry;

    // Optional: absent when metrics are not on the classpath (e.g. in plain unit tests).
    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        normalizeNotificationSettings();
        dispatcher = new BoundedSendExecutor("apns", workers, queueCapacity, responseTimeoutMs, meterRegistry);

        if (apnsTokenKeyPath == null || apnsTeamId == null || apnsKeyId == null || apnsProdEnvironment == null) {
            log.error("Could not init APNS service. Signing key information missing.");
            isConnected = false;
            return;
        }
        try {
            String apnsHost = apnsServerHost != null ? apnsServerHost : gatewayHost(apnsProdEnvironment);
            ApnsSigningKey signingKey = ApnsSigningKey.loadFromPkcs8File(new File(apnsTokenKeyPath), apnsTeamId, apnsKeyId);
            apnsClient = buildClient(signingKey, apnsHost, apnsServerPort);
            isConnected = true;
            log.info("Started APNS client successfully (environment: {})", environmentName(apnsProdEnvironment));

            if (Boolean.TRUE.equals(apnsFallbackEnabled)) {
                startFallbackClient(signingKey);
            }
        } catch (IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            // A missing, unreadable, or malformed .p8 signing key. This is the only pre-send credential validation
            // we do, so leave the relay unhealthy rather than discovering the problem on the first user notification.
            isConnected = false;
            log.error("Could not init APNS service", e);
        }
    }

    /**
     * Falls back to the defaults for an unset/empty or invalid notification setting instead of failing. A bad value
     * (for example an empty environment variable, or a priority Apple does not define) must degrade to the previous
     * behaviour, never take the whole relay down or make every send fail.
     */
    private void normalizeNotificationSettings() {
        if (topic == null || topic.isBlank()) {
            topic = DEFAULT_TOPIC;
        }
        if (expirationDays == null || expirationDays < 1) {
            log.error("Invalid apns.expiration-days '{}'; using {}", expirationDays, DEFAULT_EXPIRATION_DAYS);
            expirationDays = DEFAULT_EXPIRATION_DAYS;
        }
        // Apple only defines priorities 10 (immediately) and 5 (power-considerate) for alert notifications.
        if (alertPriority == null || (alertPriority != 5 && alertPriority != 10)) {
            log.error("Invalid apns.alert-priority '{}' (must be 5 or 10); using {}", alertPriority, DEFAULT_ALERT_PRIORITY);
            alertPriority = DEFAULT_ALERT_PRIORITY;
        }
    }

    private void startFallbackClient(ApnsSigningKey signingKey) {
        String fallbackHost = apnsFallbackServerHost != null ? apnsFallbackServerHost : gatewayHost(!apnsProdEnvironment);
        try {
            fallbackApnsClient = buildClient(signingKey, fallbackHost, apnsFallbackServerPort);
            log.info("Started APNS fallback client successfully (environment: {})", environmentName(!apnsProdEnvironment));
        } catch (IOException e) {
            // The primary gateway still works, so the relay stays healthy; it just cannot serve the other environment.
            log.warn("Could not start the APNS fallback client; tokens of the {} environment will be rejected", environmentName(!apnsProdEnvironment), e);
        }
    }

    private ApnsClient buildClient(ApnsSigningKey signingKey, String host, int port) throws IOException {
        ApnsClientBuilder clientBuilder = new ApnsClientBuilder()
                .setApnsServer(host, port)
                .setSigningKey(signingKey)
                .setConnectionTimeout(Duration.ofMillis(connectionTimeoutMs));
        if (apnsTrustedCertPath != null) {
            clientBuilder.setTrustedServerCertificateChain(new File(apnsTrustedCertPath));
        }
        return clientBuilder.build();
    }

    private static String gatewayHost(boolean production) {
        return production ? ApnsClientBuilder.PRODUCTION_APNS_HOST : ApnsClientBuilder.DEVELOPMENT_APNS_HOST;
    }

    private static String environmentName(boolean production) {
        return production ? "production" : "development";
    }

    @Override
    public DeferredResult<ResponseEntity<Void>> send(NotificationRequest request) {
        if (dispatcher == null) {
            // Should not happen once the application is ready, but stay defensive rather than NPE.
            return BoundedSendExecutor.completed(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
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
            if (fallbackApnsClient != null && isBadDeviceToken(response)) {
                // The primary gateway answered and authenticated us (BadDeviceToken is not a credential reason), so it
                // is healthy; the token most likely belongs to the other environment.
                isConnected = true;
                if (sendViaFallback(notification)) {
                    return ResponseEntity.ok().build();
                }
            }
            return handleResponse(response);
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

    private static boolean isBadDeviceToken(PushNotificationResponse<SimpleApnsPushNotification> response) {
        return !response.isAccepted() && BAD_DEVICE_TOKEN.equals(response.getRejectionReason().orElse(null));
    }

    /**
     * Retries a notification on the gateway of the other environment. Returns {@code true} only if that gateway
     * accepted it. Every other outcome (rejection, credential problem, connection failure) returns {@code false}
     * and is deliberately kept out of the health state: the primary gateway's response is reported instead.
     */
    private boolean sendViaFallback(SimpleApnsPushNotification notification) {
        try {
            PushNotificationResponse<SimpleApnsPushNotification> response = fallbackApnsClient.sendNotification(notification).get();
            if (response.isAccepted()) {
                log.info("Sent notification via the {} gateway (the device token belongs to the other APNs environment)", environmentName(!apnsProdEnvironment));
                return true;
            }
            log.warn("Notification also rejected by the {} fallback gateway: {}", environmentName(!apnsProdEnvironment), response.getRejectionReason().orElse("unknown"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while sending push notification via the fallback gateway.", e);
        } catch (ExecutionException e) {
            log.warn("Failed to send push notification via the fallback gateway.", e);
        }
        return false;
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
                topic,
                payload.build(),
                Instant.now().plus(Duration.ofDays(expirationDays)),
                isV2Api ? DeliveryPriority.getFromCode(alertPriority) : DeliveryPriority.CONSERVE_POWER,
                isV2Api ? PushType.ALERT : PushType.BACKGROUND);
    }

    private ResponseEntity<Void> handleResponse(PushNotificationResponse<SimpleApnsPushNotification> response) {
        if (response.isAccepted()) {
            // A successful send proves the connection (and thus the signing key) works.
            isConnected = true;
            log.debug("Sent notification");
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
            log.warn("Notification rejected by the APNs gateway: {}", reason);
        }
        response.getTokenInvalidationTimestamp().ifPresent(timestamp -> log.warn("\t... and the device token is invalid as of {}", timestamp));
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
        if (fallbackApnsClient != null) {
            fallbackApnsClient.close();
        }
    }
}
