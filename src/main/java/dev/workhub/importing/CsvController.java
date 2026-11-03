package dev.workhub.importing;

import dev.workhub.identity.*;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/projects/{p}/imports")
public class CsvController {
    private final CsvImports service;
    private final CurrentActor actors;

    public CsvController(CsvImports service, CurrentActor actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping(consumes = "multipart/form-data")
    public UUID upload(
            Principal u,
            @PathVariable UUID p,
            @RequestParam MultipartFile file,
            @RequestHeader("Idempotency-Key") String key)
            throws java.io.IOException {
        return service.upload(actors.from(u), p, file.getOriginalFilename(), file.getBytes(), key);
    }

    @GetMapping
    public Object list(Principal u, @PathVariable UUID p) {
        return service.list(actors.from(u), p);
    }

    @GetMapping("/{id}")
    public Object view(Principal u, @PathVariable UUID p, @PathVariable UUID id) {
        return service.view(actors.from(u), p, id);
    }

    @PostMapping("/{id}/confirm")
    public void confirm(Principal u, @PathVariable UUID p, @PathVariable UUID id) {
        service.confirm(actors.from(u), p, id, false);
    }

    @PostMapping("/{id}/resume")
    public void resume(Principal u, @PathVariable UUID p, @PathVariable UUID id) {
        service.confirm(actors.from(u), p, id, true);
    }

    @GetMapping("/{id}/source")
    public ResponseEntity<byte[]> source(Principal u, @PathVariable UUID p, @PathVariable UUID id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=source.csv")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(service.source(actors.from(u), p, id));
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<String> report(Principal u, @PathVariable UUID p, @PathVariable UUID id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=report.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(service.report(actors.from(u), p, id));
    }
}
