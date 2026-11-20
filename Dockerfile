FROM docker.io/library/eclipse-temurin:25-jdk AS builder
WORKDIR /build
COPY . .
RUN ./gradlew hermes:bootJar --no-daemon \
    # Split the executable jar into the application jar and a lib/ directory: jdeps below needs the plain classpath,
    # and the runtime image starts without unpacking the fat jar on every boot.
    && java -Djarmode=tools -jar hermes/build/libs/hermes-1.0.0.jar extract --destination /extracted

# The runtime uses Oracle JDK 25.0.5.1 until a Temurin 25 image with the same fixes is published.
# The archive is pinned by version and verified against Oracle's published SHA-256 checksum.
FROM docker.io/library/ubuntu:26.04 AS jdk
ARG TARGETARCH
ARG JDK_VERSION=25.0.5.1
ARG JDK_SHA256_AMD64=3d40d69461200594380ac84c17be6224539022028730cffe043ac70c41ffd1d5
ARG JDK_SHA256_ARM64=2c30d5fe69796452732d32e746c5f4b4031e4991e2be9a3313f76e51fe5196ee
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN case "${TARGETARCH}" in \
        amd64) jdk_arch=x64; jdk_sha256="${JDK_SHA256_AMD64}" ;; \
        arm64) jdk_arch=aarch64; jdk_sha256="${JDK_SHA256_ARM64}" ;; \
        *) echo "Unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl -fsSL -o /tmp/jdk.tar.gz "https://download.oracle.com/java/25/archive/jdk-${JDK_VERSION}_linux-${jdk_arch}_bin.tar.gz" \
    && echo "${jdk_sha256}  /tmp/jdk.tar.gz" | sha256sum -c - \
    && mkdir -p /opt/java/jdk \
    && tar -xzf /tmp/jdk.tar.gz -C /opt/java/jdk --strip-components=1 \
    && rm /tmp/jdk.tar.gz

# Reduce the JDK to the modules the application needs (a much smaller runtime without compiler, jshell, ...).
# jdeps finds the modules the code references; the extras cover what is only loaded reflectively or through service
# loading (TLS/crypto providers, Netty's Unsafe access, JMX for metrics, DNS and Kerberos for the Google auth stack).
FROM jdk AS jre
COPY --from=builder /extracted /extracted
RUN modules="$(/opt/java/jdk/bin/jdeps --ignore-missing-deps --multi-release 25 --print-module-deps --class-path '/extracted/lib/*' /extracted/hermes-1.0.0.jar)" \
    && echo "Modules found by jdeps: ${modules}" \
    && /opt/java/jdk/bin/jlink \
        --add-modules "${modules},java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.xml,jdk.crypto.cryptoki,jdk.management,jdk.naming.dns,jdk.security.auth,jdk.unsupported,jdk.zipfs" \
        --strip-java-debug-attributes --no-man-pages --no-header-files --compress zip-6 \
        --output /opt/java/jre

FROM docker.io/library/ubuntu:26.04
# An unprivileged user for hardened setups. The image still starts as root by default, because existing deployments
# mount key files that are only readable by root. To run unprivileged, make the mounted APNs signing key and Firebase
# credentials readable by uid 10001 and start the container with `user: "10001:10001"` (see README).
RUN groupadd --system --gid 10001 hermes \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin hermes
ENV JAVA_HOME=/opt/java/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre /opt/java/jre /opt/java/jre
WORKDIR /app
COPY --from=builder /extracted/ /app/

EXPOSE 8080
# Plain bash (no curl in the image): GET the liveness endpoint and expect a 200 status line. Note that this only
# proves the process is up; use /api/health to see whether APNs and Firebase are actually connected.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080} && printf 'GET /api/push_notification/alive HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 200 '"]

ENTRYPOINT ["java", "-jar", "/app/hermes-1.0.0.jar"]
