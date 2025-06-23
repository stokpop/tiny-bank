package io.perfana.tinybank.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "transaction")
public class Transaction {

        @Id
        // generated id will break loading data wit explicit transactionId set
        //@GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long transactionId;
        private String fromAccount;
        private String toAccount;
        private Long amount;
        private String currency;
        private String description;
        private LocalDate transactionDate;

        public Transaction() {
                this(null, null, null, null, null, null, null);
        }
        public Transaction(Long transactionId, String fromAccount, String toAccount, Long amount, String currency, String description, LocalDate transactionDate) {
                this.transactionId = transactionId;
                this.fromAccount = fromAccount;
                this.toAccount = toAccount;
                this.amount = amount;
                this.currency = currency;
                this.description = description;
                this.transactionDate = transactionDate;
        }

        public Long getTransactionId() {
                return transactionId;
        }

        public void setTransactionId(Long transactionId) {
                this.transactionId = transactionId;
        }

        public String getFromAccount() {
                return fromAccount;
        }

        public void setFromAccount(String fromAccount) {
                this.fromAccount = fromAccount;
        }

        public String getToAccount() {
                return toAccount;
        }

        public void setToAccount(String toAccount) {
                this.toAccount = toAccount;
        }

        public Long getAmount() {
                return amount;
        }

        public void setAmount(Long amount) {
                this.amount = amount;
        }

        public String getCurrency() {
                return currency;
        }

        public void setCurrency(String currency) {
                this.currency = currency;
        }

        public String getDescription() {
                return description;
        }

        public void setDescription(String description) {
                this.description = description;
        }

        public LocalDate getTransactionDate() {
                return transactionDate;
        }

        public void setTransactionDate(LocalDate transactionDate) {
                this.transactionDate = transactionDate;
        }

        @Override
        public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                Transaction that = (Transaction) o;
                return Objects.equals(transactionId, that.transactionId) && Objects.equals(fromAccount, that.fromAccount) && Objects.equals(toAccount, that.toAccount) && Objects.equals(amount, that.amount) && Objects.equals(currency, that.currency) && Objects.equals(description, that.description) && Objects.equals(transactionDate, that.transactionDate);
        }

        @Override
        public int hashCode() {
                int result = Objects.hashCode(transactionId);
                result = 31 * result + Objects.hashCode(fromAccount);
                result = 31 * result + Objects.hashCode(toAccount);
                result = 31 * result + Objects.hashCode(amount);
                result = 31 * result + Objects.hashCode(currency);
                result = 31 * result + Objects.hashCode(description);
                result = 31 * result + Objects.hashCode(transactionDate);
                return result;
        }
}
