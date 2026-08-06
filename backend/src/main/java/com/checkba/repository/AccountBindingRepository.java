package com.checkba.repository;

import com.checkba.model.entity.AccountBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountBindingRepository extends JpaRepository<AccountBinding, Long> {
    Optional<AccountBinding> findByExternalAccountId(String externalAccountId);
}
