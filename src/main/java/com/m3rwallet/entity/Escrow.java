package com.m3rwallet.entity;

import lombok.*;
import jakarta.persistence.*;

@Entity
@Table(name = "escrows", indexes = {
    @Index(name = "idx_network_escrow_id", columnList = "network,escrow_id", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Escrow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 20, nullable = false)
    private String network;

    @Column(name = "escrow_id", length = 64, nullable = false)
    private String escrowId;

    @Column(length = 100, nullable = false)
    private String buyer;

    @Column(length = 100, nullable = false)
    private String seller;

    @Column(length = 100, nullable = false)
    private String arbiter;

    @Column(length = 64, nullable = false)
    private String amount;

    @Column(columnDefinition = "TEXT")
    private String rawData;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
