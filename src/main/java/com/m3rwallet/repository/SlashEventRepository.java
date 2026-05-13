package com.m3rwallet.repository;

import com.m3rwallet.entity.SlashEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SlashEventRepository extends JpaRepository<SlashEvent, Long> {
    List<SlashEvent> findByValidatorAddress(String validatorAddress);
    List<SlashEvent> findByValidatorAddressAndNetwork(String address, String network);
    List<SlashEvent> findByValidatorAddressAndNetworkOrderByCreatedAtDesc(String validatorAddress, String network);
    List<SlashEvent> findByBlockHeight(Long blockHeight);
}
