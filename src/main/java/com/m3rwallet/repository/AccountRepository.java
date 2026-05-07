package com.m3rwallet.repository;

import com.m3rwallet.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    @Query("SELECT a FROM Account a WHERE a.network = :network AND a.address = :address")
    Optional<Account> findByNetworkAndAddress(@Param("network") String network, @Param("address") String address);

    @Query("SELECT a FROM Account a WHERE a.network = :network")
    List<Account> findByNetwork(@Param("network") String network);

    @Query(value = "SELECT * FROM accounts WHERE network = :network AND address = :address FOR UPDATE", nativeQuery = true)
    Optional<Account> findForUpdate(@Param("network") String network, @Param("address") String address);
}
