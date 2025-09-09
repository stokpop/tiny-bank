package io.perfana.tinybank;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.ObservationExecChainHandler;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

import java.io.FileInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

@Configuration
public class TinyBankApplicationConfig {

    private static final Logger logger = LoggerFactory.getLogger(TinyBankApplicationConfig.class);

    @Value("${mtls.enabled:false}")
    private boolean mtlsEnabled;

    @Value("${mtls.keystore.path:}")
    private String keyStorePath;

    @Value("${mtls.keystore.password:}")
    private String keyStorePassword;

    @Value("${mtls.truststore.path:}")
    private String trustStorePath;

    @Value("${mtls.truststore.password:}")
    private String trustStorePassword;


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
            try {
                javax.net.ssl.SSLContext sslContext;
                try {
                    // Try to obtain SSL context from Spring SSL bundle first
                    SslBundle bundle = sslBundles.getBundle("mtls-client");
                    try {
                        sslContext = bundle.createSslContext();
                    } catch (NoSuchMethodError | Exception ignored) {
                        var managers = bundle.getManagers();
                        var kmf = managers.getKeyManagerFactory();
                        var tmf = managers.getTrustManagerFactory();
                        sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                        sslContext.init(kmf != null ? kmf.getKeyManagers() : null,
                                tmf != null ? tmf.getTrustManagers() : null,
                                null);
                    }
                } catch (org.springframework.boot.ssl.NoSuchSslBundleException nsbe) {
                    // Fallback: build SSLContext from explicit keystore/truststore properties
                    logger.warn("SSL bundle 'mtls-client' not found. Falling back to keystore/truststore at configured mtls.* paths: keystore={}, truststore={}", keyStorePath, trustStorePath);
                    java.security.KeyStore keyStore = java.security.KeyStore.getInstance("PKCS12");
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(keyStorePath)) {
                        keyStore.load(fis, keyStorePassword != null ? keyStorePassword.toCharArray() : new char[0]);
                    }
                    java.security.KeyStore trustStore = java.security.KeyStore.getInstance("PKCS12");
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(trustStorePath)) {
                        trustStore.load(fis, trustStorePassword != null ? trustStorePassword.toCharArray() : new char[0]);
                    }
                    sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                    javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
                    kmf.init(keyStore, keyStorePassword != null ? keyStorePassword.toCharArray() : new char[0]);
                    javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(trustStore);
                    sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
                }

                SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext);
                Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.getSocketFactory())
                        .register("https", sslSocketFactory)
                        .build();
                connectionManager = new PoolingHttpClientConnectionManager(socketFactoryRegistry);
                connectionManager.setDefaultConnectionConfig(connectionConfig);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create SSL-enabled Apache HttpClient; SSL bundle missing and fallback failed", e);
            }
        } else {
            logger.info("mTLS is disabled: using default Apache HttpClient connection manager (no client certificates)");
            connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(connectionConfig)
                    .setConnectionTimeToLive(TimeValue.ofSeconds(60))
                    .build();
        }

        new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, "tiny-bank-http-pool").bindTo(meterRegistry);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(100, TimeUnit.MILLISECONDS)
                .setConnectTimeout(100, TimeUnit.MILLISECONDS)
                .setResponseTimeout(1200, TimeUnit.MILLISECONDS)
                .build();

        var httpClientBuilder = HttpClients.custom()
                .disableAutomaticRetries()
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .addRequestInterceptorFirst(createHttpRequestInterceptor())
                .addResponseInterceptorLast(createHttpResponseInterceptor())
                .addExecInterceptorLast("micrometer", new ObservationExecChainHandler(observationRegistry));

        return httpClientBuilder.build();
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
            String uri = null;
            String path = null;
            try {
                URI requestUri = request.getUri();
                uri = requestUri.toString();
                path = requestUri.getPath();
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }

            // Store both full URI and path for Micrometer
            context.setAttribute("http.url", uri);
            context.setAttribute("http.uri", path != null ? path : uri);
            context.setAttribute("uri.template", path != null ? path : uri);
        };
    }

    @Bean
    RestClient restClient(CloseableHttpClient httpClient) {
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return RestClient.builder().requestFactory(factory).build();
    }

//    @Bean
//    public RestTemplate restTemplate(CloseableHttpClient httpClient) {
//        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
//        RestTemplate restTemplate = new RestTemplate(requestFactory);
//        restTemplate.setErrorHandler(new CustomResponseErrorHandler());
//        return restTemplate;
//    }

    @Bean
    public CloseableHttpAsyncClient httpAsyncClient() {
        ConnectionConfig connectionConfig = ConnectionConfig.custom().build();

        PoolingAsyncClientConnectionManager asyncConnMgr =
                PoolingAsyncClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(connectionConfig)
                        .setConnectionTimeToLive(TimeValue.ofSeconds(60)) // TTL like your classic client
                        .build();

        CloseableHttpAsyncClient asyncClient = HttpAsyncClients.custom()
                .setConnectionManager(asyncConnMgr)
                // Optional proactive eviction (helps enforce TTL/idle in practice)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();

        asyncClient.start();
        return asyncClient;
    }

    @Bean
    public WebClient webClient(CloseableHttpAsyncClient httpAsyncClient) {
        ClientHttpConnector connector = new HttpComponentsClientHttpConnector(httpAsyncClient);
        return WebClient.builder()
                .clientConnector(connector)
                // Optional: default headers, baseUrl, codecs, filters, etc.
                .build();
    }
}