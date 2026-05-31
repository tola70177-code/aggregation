package com.github.botaggregation.controller;

import com.github.botaggregation.service.TdLibClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TdLibClientService tdLibClientService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        var state = tdLibClientService.getAuthState();
        var response = new java.util.HashMap<String, Object>();
        response.put("state", state.name());

        if (state == TdLibClientService.AuthState.ERROR) {
            response.put("error", tdLibClientService.getAuthError());
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/code")
    public ResponseEntity<Map<String, Object>> submitCode(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "Code is required"));
        }

        tdLibClientService.submitAuthCode(code);
        return ResponseEntity.ok(Map.of("success", true, "message", "Code submitted"));
    }

    @PostMapping("/password")
    public ResponseEntity<Map<String, Object>> submitPassword(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "Password is required"));
        }

        tdLibClientService.submitAuthPassword(password);
        return ResponseEntity.ok(Map.of("success", true, "message", "Password submitted"));
    }
}
