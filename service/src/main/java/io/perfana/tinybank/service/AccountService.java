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

    @Value("${remote.account.service.url}")
    private String remoteServiceUrl;

    private final RestTemplate restTemplate;

    public AccountService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @CircuitBreaker(name = ACCOUNT_SERVICE, fallbackMethod = "getAccountFallback")
    public Account getAccount(String userId) {
        logger.info("Calling account service for user: {}", userId);
        String url = String.format("%s/account?userId=%s", remoteServiceUrl, userId);
        return restTemplate.getForObject(url, Account.class);
    }

    private Account getAccountFallback(String userId, Exception ex) {
        logger.warn("Fallback for getAccount called for user: {}. Error: {}", userId, ex.getMessage());
        // Return a default account as fallback
        Customer fallbackCustomer = new Customer("Fallback User");
        return new Account(fallbackCustomer, "FALLBACK-ACCOUNT", "Fallback Account");
    }
}
