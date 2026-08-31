package io.github.trialiya.kb.model.project;

import java.nio.file.Path;

/**
 * One skill a project defines — {@code kb.projects[].skills[]} as resolved by {@code
 * ProjectCatalog}: the file path is absolute and proven to sit inside the project tree, so nothing
 * downstream re-derives or re-checks either.
 *
 * <p>Only the description lives here; the text does not. It is read from the working tree at each
 * {@code readSkill} call ({@code SkillService}), so it moves with the repository — a pull or a
 * branch switch updates the skill with no restart — and its absence is a tool error at call time,
 * not a startup failure over a file some branch legitimately lacks.
 *
 * @param name key the model loads the skill by; unique within the project and never equal to a
 *     built-in skill's name ({@code SkillService} refuses to start otherwise)
 * @param trigger when to load it — the catalogue line after the name, shown in the {@code
 *     <active-project>} block while this project is active
 * @param file absolute, normalized path of the markdown inside the project tree
 */
public record ProjectSkill(String name, String trigger, Path file) {}
