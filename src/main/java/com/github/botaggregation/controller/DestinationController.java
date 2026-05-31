package com.github.botaggregation.controller;

import com.github.botaggregation.dto.SetDestinationRequest;
import com.github.botaggregation.entity.DestinationChannel;
import com.github.botaggregation.repository.DestinationChannelRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/destination")
@RequiredArgsConstructor
public class DestinationController {

    private final DestinationChannelRepository destinationChannelRepository;

    @PutMapping
    public ResponseEntity<Map<String, Object>> setDestination(@Valid @RequestBody SetDestinationRequest request) {
        var existing = destinationChannelRepository.findCurrent();

        DestinationChannel channel;
        if (existing.isPresent()) {
            channel = existing.get();
            channel.setChannelId(request.getChannelId());
        } else {
            channel = new DestinationChannel();
            channel.setChannelId(request.getChannelId());
        }

        destinationChannelRepository.save(channel);

        return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                "channelId", channel.getChannelId()
        )));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getDestination() {
        var destination = destinationChannelRepository.findCurrent();
        if (destination.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", true, "data", Map.of()));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                "channelId", destination.get().getChannelId()
        )));
    }
}
