package de.tum.cit.artemis.push.apns;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the expiry date of the APNs client certificate (a PKCS#12 keystore) so that the relay can report an
 * expired certificate proactively — before the first send fails — and detect an expiry that occurs while the
 * process keeps running. This is pure file I/O and never touches the network, so it can never block or starve
 * the health endpoint.
 */
public class ApnsCertificateInspector {

    private static final Logger log = LoggerFactory.getLogger(ApnsCertificateInspector.class);

    /**
     * Reads the keystore and returns the earliest {@code notAfter} across all certificates it contains. The
     * earliest is used so a chain with one already-expired certificate is reported as expired.
     *
     * @param keystorePath     the path to the PKCS#12 keystore (the APNs certificate)
     * @param keystorePassword the keystore password
     * @return the earliest expiry instant, or empty if the keystore is missing/unreadable or has no certificate
     */
    public Optional<Instant> earliestExpiry(String keystorePath, String keystorePassword) {
        if (keystorePath == null || keystorePassword == null) {
            return Optional.empty();
        }
        try (InputStream in = new FileInputStream(keystorePath)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, keystorePassword.toCharArray());
            Instant earliest = null;
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                Certificate certificate = keyStore.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate x509Certificate) {
                    Instant notAfter = x509Certificate.getNotAfter().toInstant();
                    if (earliest == null || notAfter.isBefore(earliest)) {
                        earliest = notAfter;
                    }
                }
            }
            return Optional.ofNullable(earliest);
        }
        catch (IOException | GeneralSecurityException e) {
            log.warn("Could not read APNs certificate expiry from {}", keystorePath, e);
            return Optional.empty();
        }
    }

    /**
     * @return true if the certificate is expired, i.e. {@code expiry} is at or before {@code now}
     */
    public static boolean isExpired(Instant expiry, Instant now) {
        return !expiry.isAfter(now);
    }
}
