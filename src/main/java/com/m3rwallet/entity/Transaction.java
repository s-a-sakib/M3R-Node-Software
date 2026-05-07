package com.m3rwallet.entity;

import lombok.*;
import jakarta.persistence.*;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_network_hash", columnList = "network,hash", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 20, nullable = false)
    private String network;

    @Column(length = 64, nullable = false)
    private String hash;

    @Column(length = 50, nullable = false)
    private String status; // PENDING, CONFIRMED, REJECTED

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
