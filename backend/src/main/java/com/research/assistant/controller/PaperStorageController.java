package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.service.PaperStorageDirectoryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

/** Local-only operations for the configured paper file directory. */
@RestController
@RequestMapping("/api/papers/storage-directory")
public class PaperStorageController {

    private final PaperStorageDirectoryService storageDirectoryService;

    public PaperStorageController(PaperStorageDirectoryService storageDirectoryService) {
        this.storageDirectoryService = storageDirectoryService;
    }

    @PostMapping("/open")
    public Result<Map<String, String>> openDirectory() {
        Path directory = storageDirectoryService.openDirectory();
        return Result.ok(Map.of("path", directory.toString()));
    }
}
