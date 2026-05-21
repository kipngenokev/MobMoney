package com.mobmoney.transfer.repository;

import com.mobmoney.transfer.domain.TransferTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferTransactionRepository extends JpaRepository<TransferTransaction, Long> {

    Optional<TransferTransaction> findByReference(String reference);

    Page<TransferTransaction> findBySourceAccountNumberOrDestinationAccountNumberOrderByCreatedAtDesc(
            String sourceAccountNumber, String destinationAccountNumber, Pageable pageable);
}
