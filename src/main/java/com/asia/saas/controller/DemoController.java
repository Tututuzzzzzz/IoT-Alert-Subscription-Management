package com.asia.saas.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    @GetMapping("/user")
    public ResponseEntity<String> userEndpoint() {
        return ResponseEntity.ok("Hello User! You have successfully accessed a secured user endpoint.");
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> adminEndpoint() {
        return ResponseEntity.ok("Hello Admin! You have successfully accessed a secured admin-only endpoint.");
    }

    @GetMapping("/device")
    @PreAuthorize("hasRole('DEVICE')")
    public ResponseEntity<String> deviceEndpoint() {
        return ResponseEntity.ok("Hello Device! You have successfully accessed a secured device-only endpoint.");
    }
}
