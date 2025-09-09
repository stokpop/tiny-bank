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
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

@Configuration
public class TinyBankApplicationConfig {

    private static final Logger logger = LoggerFactory.getLogger(TinyBankApplicationConfig.class);

    @Bean
    public CloseableHttpClient httpClient(ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        // Define defaults that do NOT include TTL; TTL is set on the connection manager itself.
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                // other per-connection defaults go here (socket buffer sizes, etc.)
                .setTimeToLive(TimeValue.ofSeconds(60)) // TTL for persistent connections
                .setConnectTimeout(Timeout.ofMilliseconds(300))
                .build();

        // Use builder to set connection time-to-live (TTL)
//        PoolingHttpClientConnectionManager connectionManager =
//                PoolingHttpClientConnectionManagerBuilder.create()
//                        .setDefaultConnectionConfig(config)
//                        .setConnectionTimeToLive(TimeValue.ofSeconds(60)) // TTL for persistent connections
//                        .build();

        var connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setDefaultConnectionConfig(connectionConfig);

        new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, "tiny-bank-http-pool").bindTo(meterRegistry);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(100, TimeUnit.MILLISECONDS)
                .setConnectTimeout(100, TimeUnit.MILLISECONDS)
                .setResponseTimeout(1200, TimeUnit.MILLISECONDS)
                .build();

        return HttpClients.custom()
                .disableAutomaticRetries()
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .addRequestInterceptorFirst(createHttpRequestInterceptor())
                .addResponseInterceptorLast(createHttpResponseInterceptor())
                .addExecInterceptorLast("micrometer", new ObservationExecChainHandler(observationRegistry))
                .build();
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

//    @Bean
//    RestClient restClient(CloseableHttpClient httpClient) {
//        var requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
//        return RestClient.builder().requestFactory(requestFactory).build();
//    }

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