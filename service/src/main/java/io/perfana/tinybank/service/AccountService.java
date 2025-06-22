package io.perfana.tinybank.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.perfana.tinybank.domain.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import io.perfana.tinybank.domain.Account;

@Service
public class AccountService {
    private static final Logger logger = LoggerFactory.getLogger(AccountService.class);
    private static final String ACCOUNT_SERVICE = "accountService";

    public static final Customer FALLBACK_USER = new Customer("Fallback User");
    public static final Account FALLBACK_ACCOUNT = new Account(FALLBACK_USER, "FALLBACK-ACCOUNT", "Fallback Account");

    @Value("${remote.account.service.url}")
    private String remoteServiceUrl;

    private final RestTemplate restTemplate;

    public AccountService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    //@CircuitBreaker(name = ACCOUNT_SERVICE, fallbackMethod = "getAccountFallback")
    @CircuitBreaker(name = ACCOUNT_SERVICE)
    public Account getAccount(String userId) {
        long startTimeMillis = System.currentTimeMillis();
        String url = String.format("%s/account?userId=%s", remoteServiceUrl, userId);
        Account account = restTemplate.getForObject(url, Account.class);
        logger.info("Called account service for user: {} account: {} duration: {}ms", userId, account, System.currentTimeMillis() - startTimeMillis);
        return account;

    }

    private Account getAccountFallback(String userId, Exception ex) {
        logger.warn("Fallback for getAccount called for user: {}.", userId, ex);
        // Return a default account as fallback
        return FALLBACK_ACCOUNT;
    }
}
