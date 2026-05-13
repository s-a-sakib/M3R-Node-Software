package com.m3rwallet.repository;

import com.m3rwallet.entity.ValidatorWeight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ValidatorWeightRepository extends JpaRepository<ValidatorWeight, Long> {
    Optional<ValidatorWeight> findByValidatorAddressAndNetworkAndIsStale(String address, String network, Boolean isStale);
    List<ValidatorWeight> findByNetworkAndIsStale(String network, Boolean isStale);
    List<ValidatorWeight> findByValidatorAddressAndNetwork(String address, String network);
    void deleteByValidatorAddressAndNetwork(String address, String network);
}
