package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.doc.dto.CreateDocumentRequest;
import io.github.trialiya.kb.model.doc.dto.Document;
import io.github.trialiya.kb.model.doc.dto.DocumentNode;
import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.doc.dto.UpdateDocumentRequest;
import io.github.trialiya.kb.service.DocumentExportService;
import io.github.trialiya.kb.service.DocumentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;
    private final DocumentExportService documentExportService;

    @GetMapping("/documents/tree")
    public List<DocumentNode> getTree() {
        return service.getTree();
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public Document createDocument(@RequestBody CreateDocumentRequest request) {
        return service.create(request);
    }

    @PutMapping("/documents/{id}")
    public Document updateDocument(
            @PathVariable String id, @RequestBody UpdateDocumentRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String id) {
        service.delete(id);
    }

    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam String q, @RequestParam(defaultValue = "hybrid") String mode) {
        return service.search(q);
    }

    @PostMapping("/admin/export")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void export() {
        documentExportService.exportAll();
    }
}
