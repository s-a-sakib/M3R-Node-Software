package com.m3rwallet.repository;

import com.m3rwallet.entity.ProposerEarning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProposerEarningRepository extends JpaRepository<ProposerEarning, Long> {
    List<ProposerEarning> findByProposerAddressAndNetwork(String address, String network);

    @Query("SELECT COALESCE(SUM(p.totalConsensusFee), 0) FROM ProposerEarning p WHERE p.proposerAddress = :address AND p.network = :network")
    Long sumTotalConsensusFeeByProposerAddressAndNetwork(@Param("address") String address, @Param("network") String network);
}
