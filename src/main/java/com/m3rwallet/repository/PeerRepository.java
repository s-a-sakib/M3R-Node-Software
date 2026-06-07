package com.m3rwallet.repository;

import com.m3rwallet.entity.Peer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PeerRepository extends JpaRepository<Peer, Long> {
    List<Peer> findByNetworkAndIsAlive(String network, Boolean isAlive);
    Optional<Peer> findByPeerUrlAndNetwork(String peerUrl, String network);
    List<Peer> findByNetwork(String network);
    void deleteByPeerUrlAndNetwork(String peerUrl, String network);
    List<Peer> findByNetworkAndIsAliveAndFailCountLessThan(String network, Boolean isAlive, Integer maxFail);
}
