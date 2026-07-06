package io.perfana.tinybank.service;

import io.perfana.tinybank.domain.Balance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class BalanceService {
    private static final Logger logger = LoggerFactory.getLogger(BalanceService.class);

    @Value("${remote.balance.service.url.http}")
    private String remoteServiceUrlHttp;

    @Value("${remote.balance.service.url.https}")
    private String remoteServiceUrlHttps;

    @Value("${mtls.enabled:false}")
    private boolean mtlsEnabled;

    private final RestClient restClient;

    public BalanceService(RestClient restClient) {
        this.restClient = restClient;
    }

    public Balance getBalance(String accountNumber) {
        long startTimeMillis = System.currentTimeMillis();
        String baseUrl = mtlsEnabled ? remoteServiceUrlHttps : remoteServiceUrlHttp;
        String url = String.format("%s/balance?accountNumber=%s", baseUrl, accountNumber);
        Balance balance = restClient.get().uri(url).retrieve().body(Balance.class);
        logger.info("Called balance service for account: {} balance: {} duration: {}ms", accountNumber, balance, System.currentTimeMillis() - startTimeMillis);
        return balance;
    }

}
