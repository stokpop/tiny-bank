package io.perfana.tinybank;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.ObservationExecChainHandler;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import io.micrometer.observation.ObservationRegistry;
import io.perfana.tinybank.service.CustomResponseErrorHandler;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

@Configuration
public class TinyBankApplicationConfig {

    @Bean
    public CloseableHttpClient httpClient(ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, "tiny-bank-http-pool").bindTo(meterRegistry);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(100, TimeUnit.MILLISECONDS)
                .build();

        return HttpClientBuilder.create()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(connectionManager)
                .addExecInterceptorLast("micrometer", new ObservationExecChainHandler(observationRegistry))
                .build();
    }

    @Bean
    public RestTemplate restTemplate(CloseableHttpClient httpClient) {
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setErrorHandler(new CustomResponseErrorHandler());
        return restTemplate;
    }

}
