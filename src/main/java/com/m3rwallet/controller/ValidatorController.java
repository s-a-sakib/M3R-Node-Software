package com.m3rwallet.controller;

import com.m3rwallet.entity.Validator;
import com.m3rwallet.entity.Validator.ValidatorStatus;
import com.m3rwallet.repository.ValidatorRepository;
import com.m3rwallet.service.SlashDetectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/validator")
@Slf4j
public class ValidatorController {

    private final ValidatorRepository validatorRepository;
    private final SlashDetectionService slashDetectionService;
    private final String defaultNetwork;
    private final long minimumStake;

    public ValidatorController(ValidatorRepository validatorRepository,
                               SlashDetectionService slashDetectionService,
                               @Value("${app.blockchain.network:mainnet}") String defaultNetwork,
                               @Value("${app.validator.minimum-stake:1000}") long minimumStake) {
        this.validatorRepository = validatorRepository;
        this.slashDetectionService = slashDetectionService;
        this.defaultNetwork = defaultNetwork;
        this.minimumStake = minimumStake;
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody Map<String, Object> body) {
        String address = (String) body.get("address");
        Number stakeNum = (Number) body.getOrDefault("stakedAmount", 0);
        String network = (String) body.getOrDefault("network", defaultNetwork);
        long staked = stakeNum == null ? 0L : stakeNum.longValue();
        if (address == null || address.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "address required"));
        }
        if (staked < minimumStake) {
            return ResponseEntity.badRequest().body(Map.of("message", "stakedAmount below minimum"));
        }
        Optional<Validator> opt = validatorRepository.findByAddressAndNetwork(address, network);
        if (opt.isPresent()) {
            Validator v = opt.get();
            if (v.getStatus() == ValidatorStatus.ACTIVE) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Already registered and active"));
            }
            v.setStatus(ValidatorStatus.ACTIVE);
            v.setStakedAmount(BigDecimal.valueOf(staked));
            v.setJailedUntil(null);
            validatorRepository.save(v);
            return ResponseEntity.ok(Map.of("message", "Re-activated"));
        }
        Validator v = new Validator();
        v.setAddress(address);
        v.setNetwork(network);
        v.setStakedAmount(BigDecimal.valueOf(staked));
        v.setStatus(ValidatorStatus.ACTIVE);
        v.setRegisteredAt(Instant.now().toEpochMilli());
        v.setReliabilityScoreScaled(0L);
        v.setTotalProposals(0L);
        v.setSuccessfulProposals(0L);
        v.setCorruptedProposals(0L);
        validatorRepository.save(v);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "registered"));
    }

    @GetMapping("/{address}")
    public ResponseEntity<?> getValidator(@PathVariable String address,
                                          @RequestParam(required = false) String network) {
        String net = network == null ? defaultNetwork : network;
        Optional<Validator> opt = validatorRepository.findByAddressAndNetwork(address, net);
        if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "not found"));
        Validator v = opt.get();
        boolean can = slashDetectionService.canParticipate(v.getAddress(), net);
        Map<String, Object> resp = new HashMap<>();
        resp.put("validator", v);
        resp.put("canParticipate", can);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/list")
    public ResponseEntity<?> listValidators(@RequestParam(required = false) String network) {
        String net = network == null ? defaultNetwork : network;
        List<Validator> list = validatorRepository.findByNetwork(net);
        Map<String, Object> resp = Map.of(
                "network", net,
                "count", list.size(),
                "validators", list
        );
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/{address}/slashes")
    public ResponseEntity<?> getSlashes(@PathVariable String address,
                                       @RequestParam(required = false) String network) {
        String net = network == null ? defaultNetwork : network;
        return ResponseEntity.ok(slashDetectionService.getSlashHistory(address, net));
    }

    @PostMapping("/{address}/release")
    @Transactional
    public ResponseEntity<?> release(@PathVariable String address,
                                     @RequestParam(required = false) String network) {
        String net = network == null ? defaultNetwork : network;
        boolean released = slashDetectionService.releaseFromJail(address, net);
        return ResponseEntity.ok(Map.of("released", released));
    }
}
