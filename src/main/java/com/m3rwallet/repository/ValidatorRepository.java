package com.m3rwallet.repository;

import com.m3rwallet.entity.Validator;
import com.m3rwallet.entity.Validator.ValidatorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ValidatorRepository extends JpaRepository<Validator, Long> {
    Optional<Validator> findByAddressAndNetwork(String address, String network);
    List<Validator> findByNetworkAndStatus(String network, ValidatorStatus status);
    List<Validator> findByNetwork(String network);
    List<Validator> findByStatus(ValidatorStatus status);
}
