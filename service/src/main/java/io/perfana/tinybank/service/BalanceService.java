package io.perfana.tinybank.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.perfana.tinybank.domain.Balance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.Objects;

@Service
public class BalanceService {
    private static final Logger logger = LoggerFactory.getLogger(BalanceService.class);
    private static final String BALANCE_SERVICE = "balanceService";
    public static final Balance BALANCE_UNAVAILABLE = new Balance(0, "Not Available");

    @Value("${remote.balance.service.url}")
    private String remoteServiceUrl;

    private final RestClient restClient;

    public BalanceService(RestClient restClient) {
        this.restClient = restClient;
    }

    //@CircuitBreaker(name = BALANCE_SERVICE, fallbackMethod = "getBalanceFallback")
    @CircuitBreaker(name = BALANCE_SERVICE)
    public Balance getBalance(String accountNumber) {
        long startTimeMillis = System.currentTimeMillis();
        logger.info("Calling balance service for account: {}", accountNumber);
        if (Objects.equals(accountNumber, AccountService.FALLBACK_ACCOUNT.accountNumber())) {
            return BALANCE_UNAVAILABLE;
        }
        String url = String.format("%s/balance?accountNumber=%s", remoteServiceUrl, accountNumber);
        Balance balance = restClient.get().uri(url).retrieve().body(Balance.class);
        logger.info("Called balance service for account: {} balance: {} duration: {}ms", accountNumber, balance, System.currentTimeMillis() - startTimeMillis);
        return balance;
    }

    private Balance getBalanceFallback(String accountNumber, Exception ex) {
        logger.warn("Fallback for getBalance called for account: {}.", accountNumber, ex);
        // Return a "not available" balance as fallback to avoid confusing customers with 0 EUR
        return BALANCE_UNAVAILABLE;
    }
}
