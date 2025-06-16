package io.perfana.tinybank.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.perfana.tinybank.domain.Balance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class BalanceService {
    private static final Logger logger = LoggerFactory.getLogger(BalanceService.class);
    private static final String BALANCE_SERVICE = "balanceService";

    @Value("${remote.balance.service.url}")
    private String remoteServiceUrl;

    private final RestTemplate restTemplate;

    public BalanceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @CircuitBreaker(name = BALANCE_SERVICE, fallbackMethod = "getBalanceFallback")
    public Balance getBalance(String accountNumber) {
        logger.info("Calling balance service for account: {}", accountNumber);
        String url = String.format("%s/balance?accountNumber=%s", remoteServiceUrl, accountNumber);
        return restTemplate.getForObject(url, Balance.class);
    }

    private Balance getBalanceFallback(String accountNumber, Exception ex) {
        logger.warn("Fallback for getBalance called for account: {}. Error: {}", accountNumber, ex.getMessage());
        // Return a "not available" balance as fallback to avoid confusing customers with 0 EUR
        return new Balance(0, "Not Available");
    }
}
