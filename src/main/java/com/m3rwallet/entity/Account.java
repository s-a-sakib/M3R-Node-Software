package com.m3rwallet.entity;

import lombok.*;
import jakarta.persistence.*;

@Entity
@Table(name = "accounts", indexes = {
    @Index(name = "idx_network_address", columnList = "network,address", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 20, nullable = false)
    private String network;

    @Column(length = 100, nullable = false)
    private String address;

    @Column(length = 64, nullable = false)
    private String balance; // BigInt as String to preserve precision

    @Column(nullable = false)
    private Long nonce;
}
