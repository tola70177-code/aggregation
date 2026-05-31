package com.github.botaggregation.repository;

import com.github.botaggregation.entity.TelegramAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelegramAccountRepository extends JpaRepository<TelegramAccount, Long> {

    Optional<TelegramAccount> findFirstByActiveTrue();
}
