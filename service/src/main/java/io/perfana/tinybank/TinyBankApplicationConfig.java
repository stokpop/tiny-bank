package io.perfana.tinybank;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.ObservationExecChainHandler;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

@Configuration
public class TinyBankApplicationConfig {

    private static final Logger logger = LoggerFactory.getLogger(TinyBankApplicationConfig.class);

    @Value("${mtls.enabled:false}")
    private boolean mtlsEnabled;

    @Bean
    public CloseableHttpClient httpClient(ObservationRegistry observationRegistry, MeterRegistry meterRegistry, SslBundles sslBundles) {

        // Define defaults that do NOT include TTL; TTL is set on the connection manager itself.
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setTimeToLive(TimeValue.ofSeconds(60)) // TTL for persistent connections
                .setConnectTimeout(Timeout.ofMilliseconds(300))
                .build();

        // Build connection manager with optional TLS strategy from SSL bundle
        PoolingHttpClientConnectionManager connectionManager;
        if (mtlsEnabled) {
            logger.info("mTLS is enabled: configuring Apache HttpClient with SSLContext from Spring SSL bundle");
            connectionManager = createConnectionManagerWithMtls(sslBundles, connectionConfig);
        } else {
            logger.info("mTLS is disabled: using default Apache HttpClient5 connection manager (no client certificates)");
            connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(connectionConfig)
                    //.setConnectionTimeToLive(TimeValue.ofSeconds(60)) // -> DEPRECATED
                    .build();
        }

        // Tune pool sizes to ensure reuse under concurrency
        connectionManager.setMaxTotal(100);
        connectionManager.setDefaultMaxPerRoute(50);

        new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, "tiny-bank-http-pool").bindTo(meterRegistry);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(100))
                //.setConnectTimeout(Timeout.ofMilliseconds(100)) // -> DEPRECATED
                .setResponseTimeout(Timeout.ofMilliseconds(1200))
                .build();

        var httpClientBuilder = HttpClients.custom()
                .disableAutomaticRetries()
                .disableConnectionState() // needed for mTLS connection reuse!
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // Encourage persistent connections even when server omits keep-alive headers
                .setKeepAliveStrategy((response, context) -> TimeValue.ofSeconds(60))
                // Be explicit about intent to keep the connection alive
                .setDefaultHeaders(List.of(new BasicHeader(HttpHeaders.CONNECTION, "keep-alive")))
                .addRequestInterceptorFirst(createHttpRequestInterceptor())
                .addRequestInterceptorFirst(createHttpRequestInterceptorForLogging())
                .addResponseInterceptorLast(createHttpResponseInterceptor())
                .addExecInterceptorLast("micrometer", new ObservationExecChainHandler(observationRegistry));

        return httpClientBuilder.build();
    }

    private static PoolingHttpClientConnectionManager createConnectionManagerWithMtls(SslBundles sslBundles, ConnectionConfig connectionConfig) {
        PoolingHttpClientConnectionManager connectionManager;
        try {
            SSLContext sslContext;
            try {
                // Try to obtain SSL context from Spring SSL bundle
                SslBundle bundle = sslBundles.getBundle("mtls-client");
                sslContext = bundle.createSslContext();
            } catch (org.springframework.boot.ssl.NoSuchSslBundleException nsbe) {
                String msg = "SSL bundle 'mtls-client' not found.";
                logger.error(msg);
                throw new IllegalStateException(msg, nsbe);
            }

            TlsSocketStrategy tlsStrategy = new DefaultClientTlsStrategy(sslContext);

            connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(connectionConfig)
                    .setTlsSocketStrategy(tlsStrategy)
                    .build();

            connectionManager.setDefaultConnectionConfig(connectionConfig);

            return connectionManager;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SSL-enabled Apache HttpClient; SSL bundle missing.", e);
        }
    }

    @Bean
    RestClient restClient(CloseableHttpClient httpClient) {
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(factory).build();
    }

    private static HttpResponseInterceptor createHttpResponseInterceptor() {
        return (response, entityDetails, context) -> {
            // Ensure outcome is properly set based on status code
            int statusCode = response.getCode();
            String outcome;
            if (statusCode >= 200 && statusCode < 300) {
                outcome = "SUCCESS";
            } else if (statusCode >= 400 && statusCode < 500) {
                outcome = "CLIENT_ERROR";
            } else if (statusCode >= 500) {
                outcome = "SERVER_ERROR";
            } else {
                outcome = "UNKNOWN";
            }
            context.setAttribute("http.outcome", outcome);
        };
    }

    private static HttpRequestInterceptor createHttpRequestInterceptor() {
        return (request, entityDetails, context) -> {
            // Set URI template for proper metrics tagging
            String uri;
            String path;
            try {
                URI requestUri = request.getUri();
                uri = requestUri.toString();
                path = requestUri.getPath();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Failed to parse uri from request.", e);
            }

            // Store both full URI and path for Micrometer
            context.setAttribute("http.url", uri);
            context.setAttribute("http.uri", path != null ? path : uri);
            context.setAttribute("uri.template", path != null ? path : uri);
        };
    }

    private static HttpRequestInterceptor createHttpRequestInterceptorForLogging() {
        return (request, entityDetails, context) -> {
            logger.info("Call my httpclient for {}", request.getPath());
        };
    }

}