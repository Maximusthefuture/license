package com.example.licensing.client.k8s;

import com.example.licensing.common.codec.JsonReader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Reads the UID of the kube-system namespace and uses it as a cluster
 * identifier: it is created once, when the cluster itself is created, and
 * never changes - unlike node names, pod IPs, or anything else that churns
 * under autoscaling, which is what makes it useful as a binding target.
 *
 * <p>Needs RBAC to read namespaces, e.g.:
 *
 * <pre>{@code
 * apiVersion: rbac.authorization.k8s.io/v1
 * kind: ClusterRole
 * metadata:
 *   name: license-cluster-id-reader
 * rules:
 *   - apiGroups: [""]
 *     resources: ["namespaces"]
 *     resourceNames: ["kube-system"]
 *     verbs: ["get"]
 * }</pre>
 *
 * bound to the product's own ServiceAccount via a (Cluster)RoleBinding. If
 * that RBAC isn't granted, {@link #get()} simply returns empty - see
 * {@link com.example.licensing.client.ClusterIdPolicy} for how
 * LicenseValidator is told whether that should be fatal.
 *
 * <p>Uses only the JDK's HttpClient and the standard in-cluster service
 * account files mounted by Kubernetes into every pod - no Kubernetes
 * client library dependency.
 */
public final class KubernetesClusterIdProvider implements Supplier<Optional<UUID>> {

    private static final Path TOKEN_PATH =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final Path CA_CERT_PATH =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");

    private final HttpClient httpClient;
    private final String apiServerBaseUrl;
    private final Duration cacheTtl;

    private volatile Optional<UUID> cached;
    private volatile long cachedAtMillis;

    public KubernetesClusterIdProvider() {
        this(Duration.ofMinutes(5));
    }

    public KubernetesClusterIdProvider(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
        this.httpClient = buildHttpClient();
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        this.apiServerBaseUrl = (host != null && port != null) ? "https://" + host + ":" + port : null;
    }

    @Override
    public Optional<UUID> get() {
        long now = System.currentTimeMillis();
        Optional<UUID> local = cached;
        if (local != null && (now - cachedAtMillis) < cacheTtl.toMillis()) {
            return local;
        }
        Optional<UUID> fresh = fetch();
        cached = fresh;
        cachedAtMillis = now;
        return fresh;
    }

    private Optional<UUID> fetch() {
        if (apiServerBaseUrl == null || !Files.isReadable(TOKEN_PATH)) {
            return Optional.empty(); // not running in-cluster, or no service account token mounted
        }
        try {
            String token = Files.readString(TOKEN_PATH, StandardCharsets.UTF_8).strip();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiServerBaseUrl + "/api/v1/namespaces/kube-system"))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            Optional<String> uid = JsonReader.findStringField(response.body(), "uid");
            return uid.map(UUID::fromString);
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    private static HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        try {
            if (Files.isReadable(CA_CERT_PATH)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                Certificate cert;
                try (var in = Files.newInputStream(CA_CERT_PATH)) {
                    cert = cf.generateCertificate(in);
                }
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                ks.setCertificateEntry("kube-ca", cert);
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, tmf.getTrustManagers(), null);
                builder.sslContext(sslContext);
            }
        } catch (IOException | GeneralSecurityException e) {
            // Fall back to the default trust store; fetch() will then most
            // likely fail TLS verification against the cluster CA and
            // simply return empty, which is the safe failure mode here.
        }
        return builder.build();
    }
}
