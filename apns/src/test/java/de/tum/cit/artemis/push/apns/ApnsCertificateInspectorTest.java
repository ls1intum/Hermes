package de.tum.cit.artemis.push.apns;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

class ApnsCertificateInspectorTest {

    private final ApnsCertificateInspector inspector = new ApnsCertificateInspector();

    @Test
    void isExpiredWhenExpiryIsInThePast() {
        Instant now = Instant.parse("2026-06-18T00:00:00Z");
        assertThat(ApnsCertificateInspector.isExpired(now.minus(1, ChronoUnit.DAYS), now)).isTrue();
    }

    @Test
    void isExpiredWhenExpiryEqualsNow() {
        Instant now = Instant.parse("2026-06-18T00:00:00Z");
        assertThat(ApnsCertificateInspector.isExpired(now, now)).isTrue();
    }

    @Test
    void isNotExpiredWhenExpiryIsInTheFuture() {
        Instant now = Instant.parse("2026-06-18T00:00:00Z");
        assertThat(ApnsCertificateInspector.isExpired(now.plus(1, ChronoUnit.DAYS), now)).isFalse();
    }

    @Test
    void earliestExpiryIsEmptyForNullArguments() {
        assertThat(inspector.earliestExpiry(null, "pwd")).isEmpty();
        assertThat(inspector.earliestExpiry("/some/path.p12", null)).isEmpty();
    }

    @Test
    void earliestExpiryIsEmptyForMissingFile() {
        assertThat(inspector.earliestExpiry("/nonexistent/definitely-not-here.p12", "pwd")).isEmpty();
    }
}
