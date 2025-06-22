package io.perfana.tinybank.database;

import io.perfana.tinybank.domain.Transaction;
import io.perfana.tinybank.service.AccountService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CustomTransactionRepositoryImpl implements CustomTransactionRepository {

    private static final Logger logger = LoggerFactory.getLogger(CustomTransactionRepositoryImpl.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Transaction findLastTransaction(String accountNumber) {
        long startTimestamp = System.currentTimeMillis();
        String jpql = "SELECT t FROM Transaction t WHERE t.toAccount = :accountNumber or t.fromAccount = :accountNumber ORDER BY t.transactionDate DESC";
        TypedQuery<Transaction> query = entityManager.createQuery(jpql, Transaction.class);
        query.setParameter("accountNumber", accountNumber);
        query.setMaxResults(1);
        Transaction firstTransaction = query.getResultList().stream().findFirst().orElse(null);
        logger.debug("Find last transaction for account: {} duration: {}ms", accountNumber, System.currentTimeMillis() - startTimestamp);
        return firstTransaction;
    }

    @Override
    public List<Transaction> findTransactions(String accountNumber) {
        long startTimestamp = System.currentTimeMillis();
        String jpql = "SELECT t FROM Transaction t WHERE t.toAccount = :accountNumber or t.fromAccount = :accountNumber ORDER BY t.transactionDate DESC";
        TypedQuery<Transaction> query = entityManager.createQuery(jpql, Transaction.class);
        query.setParameter("accountNumber", accountNumber);
        List<Transaction> transactions = query.getResultList();
        logger.debug("Find transactions for account: {} duration: {}ms", accountNumber, System.currentTimeMillis() - startTimestamp);
        return transactions;
    }
}