package io.perfana.tinybank.service;

import io.perfana.tinybank.database.TransactionRepository;
import io.perfana.tinybank.domain.*;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class TinyBankService {

    private static final Logger logger = LoggerFactory.getLogger(TinyBankService.class);

    @Autowired
    private AccountService accountService;

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private TransactionRepository transactionRepository;

    @PostConstruct
    public void init() {
        logger.info("Checking and initializing sample transactions");

        // Check and create transaction 1
        if (!transactionRepository.existsById(1L)) {
            Transaction transaction1 = new Transaction(
                    1L,
                    "LT121000011234567890",
                    "DE50785612345678901234",
                    100L,
                    "EUR",
                    "toys 🧸",
                    LocalDate.parse("2024-08-25")
            );
            transactionRepository.save(transaction1);
            logger.info("Created transaction 1: toys");
        }

        // Check and create transaction 2
        if (!transactionRepository.existsById(2L)) {
            Transaction transaction2 = new Transaction(
                    2L,
                    "NL91ABNA0417164300",
                    "LT121000011234567890",
                    100L,
                    "EUR",
                    "books 📚",
                    LocalDate.parse("2024-08-26")
            );
            transactionRepository.save(transaction2);
            logger.info("Created transaction 2: books");
        }

        // Check and create transaction 3
        if (!transactionRepository.existsById(3L)) {
            Transaction transaction3 = new Transaction(
                    3L,
                    "US12BOFA0000123456",
                    "LT121000011234567890",
                    100L,
                    "EUR",
                    "music 🎵",
                    LocalDate.parse("2024-08-27")
            );
            transactionRepository.save(transaction3);
            logger.info("Created transaction 3: music");
        }

        logger.info("Transaction initialization completed");
    }

    public AccountInfo retrieveAccountInfo(String userId) {
        Account account = accountService.getAccount(userId);
        Balance balance = balanceService.getBalance(account.accountNumber());
        Transaction lastTransaction = transactionRepository.findLastTransaction(account.accountNumber());
        return new AccountInfo(account, balance, lastTransaction);
    }

    public Transactions retrieveTransactions(String userId) {
        Account account = accountService.getAccount(userId);
        List<Transaction> transactions = transactionRepository.findTransactions(account.accountNumber());
        return new Transactions(account, transactions);
    }
}
