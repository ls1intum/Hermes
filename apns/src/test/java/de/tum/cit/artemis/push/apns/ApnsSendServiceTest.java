package de.tum.cit.artemis.push.apns;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.server.MockApnsServer;
import com.eatthepath.pushy.apns.server.MockApnsServerBuilder;
import com.eatthepath.pushy.apns.server.PushNotificationHandler;
import com.eatthepath.pushy.apns.server.PushNotificationHandlerFactory;
import com.eatthepath.pushy.apns.server.RejectedNotificationException;
import com.eatthepath.pushy.apns.server.RejectionReason;
import de.tum.cit.artemis.push.common.NotificationRequest;
import de.tum.cit.artemis.push.common.PushNotificationApiType;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLSession;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full APNS send path of {@link ApnsSendService} against pushy's in-process
 * {@link MockApnsServer}, which speaks the real APNS HTTP/2 + TLS protocol. This verifies that
 * Hermes builds the correct notification (topic, push type, encrypted payload) and maps the
 * gateway's accept/reject responses onto the right HTTP status codes and health state.
 */
class ApnsSendServiceTest {

    private static final String EXPECTED_TOPIC = "de.tum.cit.ase.artemis";
    private static final String P12_PASSWORD = "password";

    private static int port;
    private static MockApnsServer mockServer;
    private static SelfSignedCertificate serverCertificate;
    private static SelfSignedCertificate clientCertificate;
    private static File clientKeyStoreFile;
    private static final RecordingHandlerFactory handlerFactory = new RecordingHandlerFactory();

    private ApnsSendService service;

    @BeforeAll
    static void startMockServer() throws Exception {
        // Generate the TLS material at runtime so no private keys are committed to the repository.
        serverCertificate = new SelfSignedCertificate("localhost");
        clientCertificate = new SelfSignedCertificate("hermes-test-client");
        clientKeyStoreFile = writeClientKeyStore(clientCertificate);

        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        mockServer = new MockApnsServerBuilder()
                .setServerCredentials(serverCertificate.certificate(), serverCertificate.privateKey(), null)
                .setHandlerFactory(handlerFactory)
                .build();
        mockServer.start(port).get();
    }

    @AfterAll
    static void stopMockServer() throws Exception {
        if (mockServer != null) {
            mockServer.shutdown().get();
        }
        if (serverCertificate != null) {
            serverCertificate.delete();
        }
        if (clientCertificate != null) {
            clientCertificate.delete();
        }
        if (clientKeyStoreFile != null) {
            Files.deleteIfExists(clientKeyStoreFile.toPath());
        }
    }

    @BeforeEach
    void setUp() {
        handlerFactory.reset();

        service = new ApnsSendService();
        ReflectionTestUtils.setField(service, "apnsCertificatePath", clientKeyStoreFile.getAbsolutePath());
        ReflectionTestUtils.setField(service, "apnsCertificatePwd", P12_PASSWORD);
        ReflectionTestUtils.setField(service, "apnsProdEnvironment", false);
        ReflectionTestUtils.setField(service, "apnsServerHost", "localhost");
        ReflectionTestUtils.setField(service, "apnsServerPort", port);
        ReflectionTestUtils.setField(service, "apnsTrustedCertPath", serverCertificate.certificate().getAbsolutePath());
        applyExecutorConfig(service);

        service.applicationReady();
        assertThat(service.isHealthy()).as("client should initialise successfully").isTrue();
    }

    /** Supplies the bounded-executor settings that Spring would normally inject from @Value defaults. */
    private static void applyExecutorConfig(ApnsSendService service) {
        ReflectionTestUtils.setField(service, "workers", 4);
        ReflectionTestUtils.setField(service, "queueCapacity", 100);
        ReflectionTestUtils.setField(service, "responseTimeoutMs", 30_000L);
        ReflectionTestUtils.setField(service, "connectionTimeoutMs", 2_000L);
    }

    /** Packages the generated client certificate and key into a temporary PKCS#12 keystore. */
    private static File writeClientKeyStore(SelfSignedCertificate certificate) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("hermes-test", certificate.key(), P12_PASSWORD.toCharArray(),
                new Certificate[]{certificate.cert()});
        File file = File.createTempFile("hermes-apns-client", ".p12");
        file.deleteOnExit();
        try (OutputStream out = new FileOutputStream(file)) {
            keyStore.store(out, P12_PASSWORD.toCharArray());
        }
        return file;
    }

    @AfterEach
    void tearDown() {
        Object client = ReflectionTestUtils.getField(service, "apnsClient");
        if (client instanceof ApnsClient apnsClient) {
            apnsClient.close();
        }
    }

    @Test
    void sendsV2NotificationAsAlertWithEncryptedPayload() {
        NotificationRequest request = new NotificationRequest("iv-123", "cipher-abc", "device-token-1", PushNotificationApiType.IOS_V2);

        ResponseEntity<Void> response = service.doSend(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(service.isHealthy()).isTrue();
        assertThat(header("apns-topic")).isEqualTo(EXPECTED_TOPIC);
        assertThat(header("apns-push-type")).isEqualToIgnoringCase("alert");
        assertThat(handlerFactory.lastPayload)
                .contains("\"iv\":\"iv-123\"")
                .contains("\"payload\":\"cipher-abc\"")
                .contains("mutable-content")
                .contains("There is a new notification in Artemis.");
    }

    @Test
    void sendsDefaultNotificationAsBackgroundContentAvailable() {
        NotificationRequest request = new NotificationRequest("iv-9", "cipher-9", "device-token-2", PushNotificationApiType.DEFAULT);

        ResponseEntity<Void> response = service.doSend(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(header("apns-push-type")).isEqualToIgnoringCase("background");
        assertThat(handlerFactory.lastPayload)
                .contains("\"iv\":\"iv-9\"")
                .contains("\"payload\":\"cipher-9\"")
                .contains("content-available");
    }

    @Test
    void rejectedNotificationReturnsExpectationFailedButStaysHealthy() {
        handlerFactory.rejectWith = RejectionReason.BAD_DEVICE_TOKEN;

        ResponseEntity<Void> response = service.doSend(new NotificationRequest("iv", "p", "bad-token", PushNotificationApiType.IOS_V2));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
        // A bad device token is not a certificate problem, so the gateway is still considered healthy.
        assertThat(service.isHealthy()).isTrue();
    }

    @Test
    void badCertificateEnvironmentRejectionMarksServiceUnhealthy() {
        handlerFactory.rejectWith = RejectionReason.BAD_CERTIFICATE_ENVIRONMENT;

        ResponseEntity<Void> response = service.doSend(new NotificationRequest("iv", "p", "token", PushNotificationApiType.IOS_V2));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
        assertThat(service.isHealthy()).as("a certificate/environment rejection must flag the gateway as unhealthy").isFalse();
    }

    @Test
    void reportsUnhealthyOnceTheLoadedCertificateIsExpired() {
        // The service initialised healthy against a valid certificate ...
        assertThat(service.isHealthy()).isTrue();

        // ... but the moment the loaded certificate is past its expiry, health must report unhealthy immediately,
        // even without any failing send (this is the proactive expired-certificate detection).
        ReflectionTestUtils.setField(service, "loadedCertificateExpiry", Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThat(service.isHealthy()).isFalse();
    }

    @Test
    void handshakeFailureMarksServiceUnhealthy() throws Exception {
        // An expired/invalid client certificate fails the TLS handshake, which pushy surfaces as an
        // ExecutionException on the send. We reproduce a handshake failure deterministically by pointing a fresh
        // service at the real mock server but trusting the WRONG server certificate, so the TLS handshake is
        // rejected. The send must fail and flip health to unhealthy (the bug the original code missed: it only
        // checked rejection reasons and never the handshake/connection failure path).
        ApnsSendService failing = new ApnsSendService();
        ReflectionTestUtils.setField(failing, "apnsCertificatePath", clientKeyStoreFile.getAbsolutePath());
        ReflectionTestUtils.setField(failing, "apnsCertificatePwd", P12_PASSWORD);
        ReflectionTestUtils.setField(failing, "apnsProdEnvironment", false);
        ReflectionTestUtils.setField(failing, "apnsServerHost", "localhost");
        ReflectionTestUtils.setField(failing, "apnsServerPort", port);
        // Trust the client's own certificate instead of the server's — the server's certificate will not be trusted.
        ReflectionTestUtils.setField(failing, "apnsTrustedCertPath", clientCertificate.certificate().getAbsolutePath());
        applyExecutorConfig(failing);
        failing.applicationReady();

        try {
            ResponseEntity<Void> response = failing.doSend(new NotificationRequest("iv", "p", "token", PushNotificationApiType.IOS_V2));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
            assertThat(failing.isHealthy()).as("a connection/handshake failure must flag the gateway as unhealthy").isFalse();
        }
        finally {
            Object client = ReflectionTestUtils.getField(failing, "apnsClient");
            if (client instanceof ApnsClient apnsClient) {
                apnsClient.close();
            }
        }
    }

    private static String header(String name) {
        Http2Headers headers = handlerFactory.lastHeaders;
        assertThat(headers).as("server should have received a notification").isNotNull();
        CharSequence value = headers.get(name);
        return value == null ? null : value.toString();
    }

    /** Records the most recently received notification and optionally rejects it with a configured reason. */
    private static final class RecordingHandlerFactory implements PushNotificationHandlerFactory {
        volatile Http2Headers lastHeaders;
        volatile String lastPayload;
        volatile RejectionReason rejectWith;

        void reset() {
            lastHeaders = null;
            lastPayload = null;
            rejectWith = null;
        }

        @Override
        public PushNotificationHandler buildHandler(SSLSession sslSession) {
            return (Http2Headers headers, ByteBuf payload) -> {
                lastHeaders = headers;
                lastPayload = payload.toString(StandardCharsets.UTF_8);
                if (rejectWith != null) {
                    throw new RejectedNotificationException(rejectWith);
                }
            };
        }
    }
}
