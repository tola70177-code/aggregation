package com.github.botaggregation.controller;

import com.github.botaggregation.entity.TelegramAccount;
import com.github.botaggregation.repository.TelegramAccountRepository;
import com.github.botaggregation.service.TdLibClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final TelegramAccountRepository telegramAccountRepository;
    private final TdLibClientService tdLibClientService;

    @PutMapping
    public ResponseEntity<Map<String, Object>> setAccount(@RequestBody Map<String, String> body) {
        String phoneNumber = body.get("phoneNumber");
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "phoneNumber is required"));
        }

        // Deactivate all existing accounts
        telegramAccountRepository.findAll().forEach(a -> {
            a.setActive(false);
            telegramAccountRepository.save(a);
        });

        // Create new active account
        var account = new TelegramAccount();
        account.setPhoneNumber(phoneNumber);
        account.setActive(true);
        telegramAccountRepository.save(account);

        // Start TDLib with the new phone number
        tdLibClientService.startWithPhone(phoneNumber);

        return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                "phoneNumber", phoneNumber
        )));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAccount() {
        var account = telegramAccountRepository.findFirstByActiveTrue();
        if (account.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", true, "data", Map.of()));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                "phoneNumber", account.get().getPhoneNumber()
        )));
    }
}
