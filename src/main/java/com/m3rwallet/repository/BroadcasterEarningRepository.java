package com.m3rwallet.repository;

import com.m3rwallet.entity.BroadcasterEarning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BroadcasterEarningRepository extends JpaRepository<BroadcasterEarning, Long> {
    List<BroadcasterEarning> findByBroadcasterAddressAndNetwork(String address, String network);

    @Query("SELECT COALESCE(SUM(b.broadcastFee), 0) FROM BroadcasterEarning b WHERE b.broadcasterAddress = :address AND b.network = :network")
    Long sumBroadcastFeeByBroadcasterAddressAndNetwork(@Param("address") String address, @Param("network") String network);
}
