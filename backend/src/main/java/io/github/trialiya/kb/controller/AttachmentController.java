package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.service.AttachmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST endpoints for attachment management.
 *
 * <h3>Upload</h3>
 *
 * <pre>
 * POST /api/documents/{documentId}/attachments   (multipart/form-data, field "file")
 * POST /api/chat/{conversationId}/attachments     (multipart/form-data, field "file")
 * </pre>
 *
 * <h3>List</h3>
 *
 * <pre>
 * GET /api/documents/{documentId}/attachments
 * GET /api/chat/{conversationId}/attachments
 * </pre>
 *
 * <h3>Single attachment</h3>
 *
 * <pre>
 * GET    /api/attachments/{id}            → metadata
 * GET    /api/attachments/{id}/content    → raw text content
 * DELETE /api/attachments/{id}
 * </pre>
 *
 * <h3>AI summarize</h3>
 *
 * <pre>
 * POST /api/attachments/{id}/summarize    → generates and stores AI summary
 * </pre>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService service;

    // ── Upload ────────────────────────────────────────────────────────────────

    @PostMapping(
            value = "/documents/{documentId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Attachment uploadForDocument(
            @PathVariable Long documentId, @RequestParam("file") MultipartFile file) {
        return service.uploadForDocument(documentId, file);
    }

    @PostMapping(
            value = "/chat/{conversationId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Attachment uploadForChat(
            @PathVariable String conversationId, @RequestParam("file") MultipartFile file) {
        return service.uploadForChat(conversationId, file);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping("/documents/{documentId}/attachments")
    public List<Attachment> listForDocument(@PathVariable Long documentId) {
        return service.findByDocument(documentId);
    }

    @GetMapping("/chat/{conversationId}/attachments")
    public List<Attachment> listForChat(@PathVariable String conversationId) {
        return service.findByConversation(conversationId);
    }

    // ── Single ────────────────────────────────────────────────────────────────

    @GetMapping("/attachments/{id}")
    public Attachment get(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping(value = "/attachments/{id}/content", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getContent(@PathVariable Long id) {
        return service.getContent(id);
    }

    @DeleteMapping("/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    // ── AI summarize ──────────────────────────────────────────────────────────

    @PostMapping("/attachments/{id}/summarize")
    public Attachment summarize(@PathVariable Long id) {
        return service.summarize(id);
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @GetMapping("/attachments/search")
    public List<Attachment> search(@RequestParam String q) {
        return service.search(q);
    }
}
