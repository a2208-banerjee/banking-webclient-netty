package com.banking.payment.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Reactor Netty — mTLS + Connection Pool Configuration
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * This is the WebClient equivalent of ApacheHttpClientConfig in App 1.
 * Same goal, completely different API.
 *
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  WebClient (fluent reactive API)                         │
 *   └─────────────────────┬────────────────────────────────────┘
 *                         │ backed by
 *   ┌─────────────────────▼────────────────────────────────────┐
 *   │  ReactorClientHttpConnector (bridge between Spring + Netty)│
 *   └─────────────────────┬────────────────────────────────────┘
 *                         │ wraps
 *   ┌─────────────────────▼────────────────────────────────────┐
 *   │  HttpClient (Reactor Netty)                              │
 *   │                                                          │
 *   │  ┌───────────────────────────────────────────────────┐   │
 *   │  │  SslContext (io.netty.handler.ssl — NOT JDK type) │   │
 *   │  │  ├── KeyManagerFactory  (client.p12)              │   │
 *   │  │  └── TrustManagerFactory (truststore.jks)         │   │
 *   │  └───────────────────────────────────────────────────┘   │
 *   │                                                          │
 *   │  ┌───────────────────────────────────────────────────┐   │
 *   │  │  ConnectionProvider (Netty connection pool)        │   │
 *   │  │  maxConnections=500, maxIdleTime=20s              │   │
 *   │  └───────────────────────────────────────────────────┘   │
 *   └──────────────────────────────────────────────────────────┘
 *
 * ── mTLS API Comparison: Apache vs Netty ─────────────────────────────────
 *
 * Apache HC5 (App 1):                  Reactor Netty (App 2):
 * ─────────────────────────────────    ─────────────────────────────────
 * SSLContextBuilder (Apache)           SslContextBuilder (Netty)
 * .loadKeyMaterial(keyStore, pass)     .keyManager(kmf)
 * .loadTrustMaterial(trustStore, null) .trustManager(tmf)
 * .build() → JDK SSLContext           .build() → Netty SslContext
 *                                      (completely different type!)
 * SSLConnectionSocketFactory           HttpClient.secure(spec ->
 * PoolingHttpClientConnectionManager     spec.sslContext(sslContext))
 * CloseableHttpClient                  ConnectionProvider (pool)
 *                                      ReactorClientHttpConnector
 *
 * Same mTLS concept — different object graph.
 *
 * ── mTLS Handshake (same flow as App 1) ──────────────────────────────────
 *
 * 1. Netty initiates TLS to https://account-service:8443
 * 2. account-service presents server.p12
 * 3. Netty verifies via TrustManagerFactory (truststore.jks) → OK
 * 4. account-service requests client cert (client-auth: need)
 * 5. Netty presents client.p12 via KeyManagerFactory ← loaded here
 * 6. account-service verifies → OK. mTLS complete.
 *
 * ── Blocking vs Non-blocking ──────────────────────────────────────────────
 *
 * Apache HC5: synchronous I/O — thread waits during TLS handshake
 * Reactor Netty: non-blocking NIO — event loop handles handshake
 *   → far more efficient under high concurrency (1000+ concurrent payments)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class NettyWebClientConfig {

    private final MtlsProperties mtlsProperties;
    private final ResourceLoader  resourceLoader;

    @Value("${services.account-service.url}")
    private String accountServiceUrl;

    @Value("${services.customer-service.url}")
    private String customerServiceUrl;

    // ── Step 1: Netty SslContext with mTLS ───────────────────────────────────

    /**
     * Builds a Netty SslContext — NOTE: this is io.netty.handler.ssl.SslContext,
     * NOT javax.net.ssl.SSLContext (the JDK type used by Apache HC5).
     * They are completely different classes despite similar names.
     *
     * KeyManagerFactory → holds our client certificate (client.p12)
     * TrustManagerFactory → holds CAs we trust (truststore.jks)
     */
    @Bean
    public SslContext nettySslContext() throws Exception {
        MtlsProperties.Keystore ks = mtlsProperties.getKeystore();
        MtlsProperties.Keystore ts = mtlsProperties.getTruststore();

        // Load client keystore — payment-service's private key + cert
        KeyStore keyStore = loadKeyStore(ks.getPath(), ks.getPassword(), ks.getType());

        // KeyManagerFactory — Netty uses this to select which cert to present
        // when the server requests a client certificate
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, ks.getPassword().toCharArray());

        // TrustManagerFactory — used to verify the server's certificate
        KeyStore trustStore = loadKeyStore(ts.getPath(), ts.getPassword(), ts.getType());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SslContextBuilder builder = SslContextBuilder.forClient()
                .keyManager(kmf)      // OUR client cert (presented to servers)
                .trustManager(tmf);   // CAs we trust (verify server cert against)

        // DEV ONLY — skip hostname verification for self-signed localhost certs
        // Remove this in production
        if (mtlsProperties.isDisableHostnameVerification()) {
            log.warn("⚠️  Netty: hostname verification DISABLED — dev only");
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        }

        SslContext sslContext = builder.build();
        log.info("Netty mTLS SslContext built — keystore: {}, truststore: {}",
                ks.getPath(), ts.getPath());
        return sslContext;
    }

    // ── Step 2: Reactor Netty Connection Pool ─────────────────────────────────

    /**
     * Netty's equivalent of Apache's PoolingHttpClientConnectionManager.
     *
     * Key difference: Netty connections are non-blocking (NIO).
     * A single event loop thread handles thousands of concurrent connections.
     * Apache uses one thread-per-connection (blocking I/O).
     */
    @Bean
    public ConnectionProvider nettyConnectionProvider() {
        return ConnectionProvider.builder("banking-pool")
                .maxConnections(500)                        // total pooled connections
                .maxIdleTime(Duration.ofSeconds(20))        // close idle after 20s
                .maxLifeTime(Duration.ofMinutes(5))         // max connection lifetime
                .pendingAcquireTimeout(Duration.ofMillis(500))  // fail fast if pool full
                .pendingAcquireMaxCount(-1)                 // unlimited pending (queue)
                .evictInBackground(Duration.ofSeconds(30))  // evict stale connections
                .build();
    }

    // ── Step 3: Netty HttpClient ──────────────────────────────────────────────

    /**
     * Configures the underlying Netty HttpClient with mTLS + timeouts.
     * This is what WebClient delegates to for actual HTTP I/O.
     *
     * Apache equivalent: CloseableHttpClient
     * Netty equivalent:  reactor.netty.http.client.HttpClient
     */
    @Bean
    public HttpClient nettyHttpClient(SslContext nettySslContext,
                                       ConnectionProvider connectionProvider) {
        return HttpClient.create(connectionProvider)
                // mTLS — attaches our SslContext to every connection
                .secure(sslSpec -> sslSpec
                        .sslContext(nettySslContext)
                        .handshakeTimeout(Duration.ofSeconds(5))
                )
                // TCP connect timeout (how long to wait for TCP connection)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                // Read/write timeouts via Netty channel handlers
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(3, TimeUnit.SECONDS))
                )
                // Response timeout (end-to-end)
                .responseTimeout(Duration.ofSeconds(5))
                // HTTP/2 is supported natively in Netty (not available in Apache HC5 by default)
                .protocol(reactor.netty.http.HttpProtocol.HTTP11); // switch to H2 if server supports
    }

    // ── Step 4: WebClient beans ───────────────────────────────────────────────

    /**
     * WebClient for account-service with mTLS.
     *
     * ReactorClientHttpConnector is the bridge between Spring's WebClient
     * and the underlying Netty HttpClient.
     * Apache equivalent: ApacheHttp5Client (Feign.Client implementation).
     */
    @Bean("accountServiceWebClient")
    public WebClient accountServiceWebClient(HttpClient nettyHttpClient) {
        return WebClient.builder()
                .baseUrl(accountServiceUrl)
                .clientConnector(new ReactorClientHttpConnector(nettyHttpClient))
                .filter(commonHeadersFilter())
                .filter(idempotencyKeyFilter())
                .filter(loggingFilter("account-service"))
                .build();
    }

    @Bean("customerServiceWebClient")
    public WebClient customerServiceWebClient(HttpClient nettyHttpClient) {
        return WebClient.builder()
                .baseUrl(customerServiceUrl)
                .clientConnector(new ReactorClientHttpConnector(nettyHttpClient))
                .filter(commonHeadersFilter())
                .filter(idempotencyKeyFilter())
                .filter(loggingFilter("customer-service"))
                .build();
    }

    // ── Filters (WebClient equivalent of Feign RequestInterceptor) ────────────

    /**
     * ExchangeFilterFunction is WebClient's equivalent of Feign's RequestInterceptor.
     *
     * Feign:   @Bean RequestInterceptor — runs before every Feign call
     * WebClient: ExchangeFilterFunction — runs before every WebClient exchange
     *
     * Both inject headers automatically without any code at the call site.
     */
    private ExchangeFilterFunction commonHeadersFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request ->
                Mono.just(ClientRequest.from(request)
                        .header("Content-Type",    "application/json")
                        .header("Accept",           "application/json")
                        .header("X-Request-Source", "payment-service")
                        .build()));
    }

    /**
     * Injects idempotency key from Reactor Context.
     * WebClient equivalent of manually adding X-Idempotency-Key in Feign interceptor.
     *
     * Key Reactor concept: deferContextual reads the context at SUBSCRIPTION time,
     * not at construction time — so each request gets its own key.
     */
    private ExchangeFilterFunction idempotencyKeyFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request ->
                Mono.deferContextual(ctx -> {
                    String key = ctx.<String>getOrDefault("idempotencyKey",
                            UUID.randomUUID().toString());
                    return Mono.just(ClientRequest.from(request)
                            .header("X-Idempotency-Key", key)
                            .build());
                }));
    }

    private ExchangeFilterFunction loggingFilter(String service) {
        return (request, next) -> {
            log.info("[{}][Netty] → {} {} (mTLS)", service, request.method(), request.url());
            return next.exchange(request)
                    .doOnNext(r -> log.info("[{}][Netty] ← {}", service, r.statusCode()));
        };
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private KeyStore loadKeyStore(String path, String password, String type) throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream is = resourceLoader.getResource(path).getInputStream()) {
            ks.load(is, password.toCharArray());
        }
        return ks;
    }
}
