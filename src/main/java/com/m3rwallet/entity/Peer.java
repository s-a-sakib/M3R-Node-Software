package com.m3rwallet.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

@Entity
@Table(name = "peers", uniqueConstraints = {@UniqueConstraint(columnNames = {"peer_url", "network"})})
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Peer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "peer_url", length = 255, nullable = false)
    private String peerUrl;

    @Column(length = 20, nullable = false)
    private String network = "mainnet";

    @Column(nullable = false)
    private Boolean isAlive = true;

    @Column(name = "last_seen_at")
    private Long lastSeenAt;

    @Column(name = "first_seen_at", nullable = false)
    private Long firstSeenAt;

    @Column(name = "fail_count", nullable = false)
    private Integer failCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Timestamp createdAt;
}
