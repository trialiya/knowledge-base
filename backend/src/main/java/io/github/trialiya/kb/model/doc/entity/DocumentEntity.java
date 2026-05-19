package io.github.trialiya.kb.model.doc.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("documents")
public class DocumentEntity {

    @Id private Long id;
    private String title;
    private String type;
    private Long parentId;
    private String description;
    private LocalDateTime updatedAt;
}
