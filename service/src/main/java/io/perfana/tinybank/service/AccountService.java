package io.perfana.tinybank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import io.perfana.tinybank.domain.Account;

@Service
public class AccountService {
    private static final Logger logger = LoggerFactory.getLogger(AccountService.class);

    @Value("${remote.account.service.url.http}")
    private String remoteServiceUrlHttp;

    @Value("${remote.account.service.url.https}")
    private String remoteServiceUrlHttps;

    @Value("${mtls.enabled:false}")
    private boolean mtlsEnabled;

    private final RestClient restClient;

    public AccountService(RestClient restClient) {
        this.restClient = restClient;
    }

    public Account getAccount(String userId) {
        long startTimeMillis = System.currentTimeMillis();
        String baseUrl = mtlsEnabled ? remoteServiceUrlHttps : remoteServiceUrlHttp;
        String url = String.format("%s/account?userId=%s", baseUrl, userId);
        Account account = restClient.get().uri(url).retrieve().body(Account.class);
        logger.info("Called account service for user: {} account: {} duration: {}ms", userId, account, System.currentTimeMillis() - startTimeMillis);
        return account;
    }
}
