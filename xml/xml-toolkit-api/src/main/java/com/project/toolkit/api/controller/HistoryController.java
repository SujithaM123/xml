package com.project.toolkit.api.controller;

import com.project.toolkit.api.entity.HistoryEntry;
import com.project.toolkit.api.service.HistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final GenerateController generateController;

    public HistoryController(HistoryService historyService, GenerateController generateController) {
        this.historyService = historyService;
        this.generateController = generateController;
    }

    @GetMapping
    public List<HistoryEntry> list() {
        return historyService.list();
    }

    @PostMapping("/{id}/rerun")
    public ResponseEntity<Map<String, String>> rerun(@PathVariable String id) throws Exception {
        HistoryEntry entry = historyService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such history entry: " + id));
        if (!"generate".equals(entry.type()) || entry.request() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only 'generate' history entries can be re-run (an 'infer' entry's original upload isn't retained)");
        }
        return generateController.generate(entry.request());
    }
}
