package de.tum.cit.artemis.push.apns;

import com.eatthepath.pushy.apns.*;
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import de.tum.cit.artemis.push.common.NotificationRequest;
import de.tum.cit.artemis.push.common.PushNotificationApiType;
import de.tum.cit.artemis.push.common.SendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class ApnsSendService implements SendService<NotificationRequest> {

    private static final Logger log = LoggerFactory.getLogger(ApnsSendService.class);

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

    private ApnsClient apnsClient;

    private boolean isConnected;
    
    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        log.info("apnsCertificatePwd: {}", apnsCertificatePwd);
        log.info("apnsCertificatePath: {}", apnsCertificatePath);
        log.info("apnsProdEnvironment: {}", apnsProdEnvironment);
        if (apnsCertificatePwd == null || apnsCertificatePath == null || apnsProdEnvironment == null) {
            log.error("Could not init APNS service. Certificate information missing.");
            return;
        }
        try {
            String apnsHost = apnsServerHost != null ? apnsServerHost
                    : (apnsProdEnvironment ? ApnsClientBuilder.PRODUCTION_APNS_HOST : ApnsClientBuilder.DEVELOPMENT_APNS_HOST);
            ApnsClientBuilder clientBuilder = new ApnsClientBuilder()
                    .setApnsServer(apnsHost, apnsServerPort)
                    .setClientCredentials(new File(apnsCertificatePath), apnsCertificatePwd);
            if (apnsTrustedCertPath != null) {
                clientBuilder.setTrustedServerCertificateChain(new File(apnsTrustedCertPath));
            }
            apnsClient = clientBuilder.build();
            isConnected = true;
            log.info("Started APNS client successfully!");
        } catch (IOException e) {
            isConnected = false;
            log.error("Could not init APNS service", e);
        }
    }

    @Override
    public ResponseEntity<Void> send(NotificationRequest request) {
        return sendApnsRequest(request);
    }

    // TODO: either we send this async (in a separate bean), but then we cannot return the response entity, or we send it sync and block the thread (as we do it now)
    // @Async
    private ResponseEntity<Void> sendApnsRequest(NotificationRequest request) {
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

        var playloadString = payload.build();

        SimpleApnsPushNotification notification = new SimpleApnsPushNotification(request.token(),
                "de.tum.cit.ase.artemis",
                playloadString,
                Instant.now().plus(Duration.ofDays(7)),
                DeliveryPriority.getFromCode(5),
                isV2Api ? PushType.ALERT : PushType.BACKGROUND);

        PushNotificationFuture<SimpleApnsPushNotification, PushNotificationResponse<SimpleApnsPushNotification>> responsePushNotificationFuture = apnsClient.sendNotification(notification);
        try {
            final PushNotificationResponse<SimpleApnsPushNotification> pushNotificationResponse = responsePushNotificationFuture.get();
            if (pushNotificationResponse.isAccepted()) {
                log.info("Send notification to {}", request.token());
                isConnected = true;
                return ResponseEntity.ok().build();
            } else {
                // APNS reports rejection reasons using Apple's documented phrases (e.g. "BadCertificate"),
                // which is exactly what getRejectionReason() returns -- not the RejectionReason enum name.
                var certificateRejectionReasons = List.of("BadCertificate", "BadCertificateEnvironment");
                if (pushNotificationResponse.getRejectionReason().isPresent() && certificateRejectionReasons.contains(pushNotificationResponse.getRejectionReason().get())) {
                    isConnected = false;
                }
                log.error("Notification rejected by the APNs gateway: {}", pushNotificationResponse.getRejectionReason());
                pushNotificationResponse.getTokenInvalidationTimestamp().ifPresent(timestamp -> log.error("\t... and the token is invalid as of {}", timestamp));
                return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
            }
        } catch (ExecutionException | InterruptedException e) {
            log.error("Failed to send push notification.", e);
            return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
        }
    }

    @Override
    public boolean isHealthy() {
        return isConnected;
    }
}
