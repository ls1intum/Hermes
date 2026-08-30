package de.tum.cit.artemis.push.apns;

import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.auth.ApnsVerificationKey;
import com.eatthepath.pushy.apns.server.MockApnsServer;
import com.eatthepath.pushy.apns.server.MockApnsServerBuilder;
import com.eatthepath.pushy.apns.server.PushNotificationHandler;
import com.eatthepath.pushy.apns.server.PushNotificationHandlerFactory;
import com.eatthepath.pushy.apns.server.RejectedNotificationException;
import com.eatthepath.pushy.apns.server.RejectionReason;
import com.eatthepath.pushy.apns.server.ValidatingPushNotificationHandlerFactory;
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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full APNS send path of {@link ApnsSendService} against pushy's in-process
 * {@link MockApnsServer}, which speaks the real APNS HTTP/2 + TLS protocol. This verifies that
 * Hermes builds the correct notification (topic, push type, encrypted payload), signs it with a valid
 * token-based (JWT) credential, and maps the gateway's accept/reject responses onto the right HTTP status
 * codes and health state.
 */
class ApnsSendServiceTest {

    private static final String EXPECTED_TOPIC = "de.tum.cit.ase.artemis";
    // Realistic 10-character Apple identifiers. Their exact values do not matter to the crypto — the signing key and
    // the verification key the mock server trusts are two halves of the same runtime-generated pair — but both sides
    // must agree, since they end up as the JWT's `iss` (team) and `kid` (key) claims.
    private static final String TEAM_ID = "TEAM123456";
    private static final String KEY_ID = "KEY1234567";

    private static int port;
    private static MockApnsServer mockServer;
    private static SelfSignedCertificate serverCertificate;
    // A second, unrelated self-signed certificate used only as a deliberately-wrong trust anchor in the
    // handshake-failure test.
    private static SelfSignedCertificate untrustedCertificate;
    // The APNs signing key pair. The private half is written to a .p8 and loaded by the service; the public half is
    // handed to the validating mock server so it can verify the JWT signature Hermes produces.
    private static KeyPair signingKeyPair;
    private static File signingKeyFile;
    private static final RecordingHandlerFactory handlerFactory = new RecordingHandlerFactory();

    private ApnsSendService service;

    @BeforeAll
    static void startMockServer() throws Exception {
        // Generate the TLS material and signing key at runtime so no private keys are committed to the repository.
        serverCertificate = new SelfSignedCertificate("localhost");
        untrustedCertificate = new SelfSignedCertificate("untrusted-host");
        signingKeyPair = generateSigningKeyPair();
        signingKeyFile = writeSigningKeyFile(signingKeyPair);

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
        if (untrustedCertificate != null) {
            untrustedCertificate.delete();
        }
        if (signingKeyFile != null) {
            Files.deleteIfExists(signingKeyFile.toPath());
        }
    }

    @BeforeEach
    void setUp() {
        handlerFactory.reset();

        service = new ApnsSendService();
        ReflectionTestUtils.setField(service, "apnsTokenKeyPath", signingKeyFile.getAbsolutePath());
        ReflectionTestUtils.setField(service, "apnsTeamId", TEAM_ID);
        ReflectionTestUtils.setField(service, "apnsKeyId", KEY_ID);
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

    /** Generates a fresh EC P-256 key pair, the curve APNs token authentication (ES256) requires. */
    private static KeyPair generateSigningKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /**
     * Writes the private key as a PEM-encoded PKCS#8 file — the same shape as the {@code .p8} Apple hands out — so the
     * service exercises its real {@code loadFromPkcs8File} path rather than a shortcut.
     */
    private static File writeSigningKeyFile(KeyPair keyPair) throws Exception {
        String base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        File file = File.createTempFile("hermes-apns-signing", ".p8");
        file.deleteOnExit();
        Files.writeString(file.toPath(), pem);
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

    /**
     * The highest-value token-auth test: routes the send through pushy's real {@link ValidatingPushNotificationHandlerFactory},
     * which verifies the JWT signature against the public half of our signing key for the expected topic. A wrong
     * signature, team ID, key ID, or topic would be rejected as InvalidProviderToken, so acceptance proves Hermes
     * produces a correctly-signed token end-to-end.
     */
    @Test
    void signsNotificationWithTokenAcceptedByValidatingServer() throws Exception {
        // APNs requires a hex device token; the validating handler enforces this and that the token is registered.
        String hexToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        ApnsVerificationKey verificationKey = new ApnsVerificationKey(KEY_ID, TEAM_ID, (ECPublicKey) signingKeyPair.getPublic());
        handlerFactory.delegateFactory = new ValidatingPushNotificationHandlerFactory(
                Map.of(EXPECTED_TOPIC, Set.of(hexToken)),
                Map.of(),
                Map.of(KEY_ID, verificationKey),
                Map.of(verificationKey, Set.of(EXPECTED_TOPIC)));

        ResponseEntity<Void> response = service.doSend(new NotificationRequest("iv", "cipher", hexToken, PushNotificationApiType.IOS_V2));

        assertThat(response.getStatusCode()).as("a correctly-signed JWT must be accepted").isEqualTo(HttpStatus.OK);
        assertThat(service.isHealthy()).isTrue();
        assertThat(header("apns-topic")).isEqualTo(EXPECTED_TOPIC);
    }

    @Test
    void rejectedNotificationReturnsExpectationFailedButStaysHealthy() {
        handlerFactory.rejectWith = RejectionReason.BAD_DEVICE_TOKEN;

        ResponseEntity<Void> response = service.doSend(new NotificationRequest("iv", "p", "bad-token", PushNotificationApiType.IOS_V2));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
        // A bad device token is not a credential problem, so the gateway is still considered healthy.
        assertThat(service.isHealthy()).isTrue();
    }

    @Test
    void invalidProviderTokenRejectionMarksServiceUnhealthy() {
        handlerFactory.rejectWith = RejectionReason.INVALID_PROVIDER_TOKEN;

        ResponseEntity<Void> response = service.doSend(new NotificationRequest("iv", "p", "token", PushNotificationApiType.IOS_V2));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
        assertThat(service.isHealthy()).as("an invalid-provider-token rejection must flag the gateway as unhealthy").isFalse();
    }

    @Test
    void missingSigningKeyLeavesServiceUnhealthy() {
        // Loading the .p8 at startup is now the ONLY pre-send credential validation, so a missing/unreadable key must
        // leave the relay unhealthy rather than appearing fine until the first user notification fails.
        ApnsSendService failing = new ApnsSendService();
        ReflectionTestUtils.setField(failing, "apnsTokenKeyPath", "/nonexistent/definitely-not-here.p8");
        ReflectionTestUtils.setField(failing, "apnsTeamId", TEAM_ID);
        ReflectionTestUtils.setField(failing, "apnsKeyId", KEY_ID);
        ReflectionTestUtils.setField(failing, "apnsProdEnvironment", false);
        applyExecutorConfig(failing);

        failing.applicationReady();

        assertThat(failing.isHealthy()).as("a missing signing key must leave the relay unhealthy").isFalse();
        // With no client built, sends are refused outright rather than silently dropped.
        ResponseEntity<Void> response = failing.doSend(new NotificationRequest("iv", "p", "token", PushNotificationApiType.IOS_V2));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void handshakeFailureMarksServiceUnhealthy() {
        // A TLS handshake failure is the credential-independent "provider unreachable" path pushy surfaces as an
        // ExecutionException on the send. We reproduce it deterministically by pointing a fresh service at the real
        // mock server but trusting the WRONG server certificate, so the TLS handshake is rejected. The send must fail
        // and flip health to unhealthy (the bug the original code missed: it only checked rejection reasons and never
        // the handshake/connection failure path).
        ApnsSendService failing = new ApnsSendService();
        ReflectionTestUtils.setField(failing, "apnsTokenKeyPath", signingKeyFile.getAbsolutePath());
        ReflectionTestUtils.setField(failing, "apnsTeamId", TEAM_ID);
        ReflectionTestUtils.setField(failing, "apnsKeyId", KEY_ID);
        ReflectionTestUtils.setField(failing, "apnsProdEnvironment", false);
        ReflectionTestUtils.setField(failing, "apnsServerHost", "localhost");
        ReflectionTestUtils.setField(failing, "apnsServerPort", port);
        // Trust an unrelated certificate instead of the server's — the server's certificate will not be trusted.
        ReflectionTestUtils.setField(failing, "apnsTrustedCertPath", untrustedCertificate.certificate().getAbsolutePath());
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

    /**
     * Records the most recently received notification and optionally rejects it with a configured reason. When a
     * {@link #delegateFactory} is set, the recorded notification is additionally forwarded to that factory's handler
     * (pushy's real validating handler) so credential/signature checks actually run.
     */
    private static final class RecordingHandlerFactory implements PushNotificationHandlerFactory {
        volatile Http2Headers lastHeaders;
        volatile String lastPayload;
        volatile RejectionReason rejectWith;
        volatile PushNotificationHandlerFactory delegateFactory;

        void reset() {
            lastHeaders = null;
            lastPayload = null;
            rejectWith = null;
            delegateFactory = null;
        }

        @Override
        public PushNotificationHandler buildHandler(SSLSession sslSession) {
            PushNotificationHandler delegate = delegateFactory != null ? delegateFactory.buildHandler(sslSession) : null;
            return (Http2Headers headers, ByteBuf payload) -> {
                lastHeaders = headers;
                // toString(Charset) does not advance the reader index, so the delegate can still read the payload.
                lastPayload = payload.toString(StandardCharsets.UTF_8);
                if (rejectWith != null) {
                    throw new RejectedNotificationException(rejectWith);
                }
                if (delegate != null) {
                    delegate.handlePushNotification(headers, payload);
                }
            };
        }
    }
}
