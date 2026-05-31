package com.github.botaggregation.controller;

import com.github.botaggregation.dto.AddChannelRequest;
import com.github.botaggregation.entity.SourceChannel;
import com.github.botaggregation.repository.SourceChannelRepository;
import com.github.botaggregation.service.TdLibClientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final SourceChannelRepository sourceChannelRepository;
    private final TdLibClientService tdLibClientService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> addChannel(@Valid @RequestBody AddChannelRequest request) {
        if (sourceChannelRepository.existsByChannelId(request.getChannelId())) {
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "message", "Channel already exists"));
        }

        var channel = new SourceChannel();
        channel.setChannelId(request.getChannelId());
        channel.setEnabled(true);
        sourceChannelRepository.save(channel);

        tdLibClientService.loadMonitoredChannels();

        return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                "id", channel.getId(),
                "channelId", channel.getChannelId()
        )));
    }

    @GetMapping("/available")
    public ResponseEntity<List<Map<String, Object>>> getAvailableChannels() {
        return ResponseEntity.ok(tdLibClientService.getSubscribedChannels());
    }

    @GetMapping
    public ResponseEntity<List<SourceChannel>> getChannels() {
        return ResponseEntity.ok(sourceChannelRepository.findAll());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteChannel(@PathVariable Long id) {
        if (!sourceChannelRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        sourceChannelRepository.deleteById(id);
        tdLibClientService.loadMonitoredChannels();

        return ResponseEntity.ok(Map.of("success", true));
    }
}
