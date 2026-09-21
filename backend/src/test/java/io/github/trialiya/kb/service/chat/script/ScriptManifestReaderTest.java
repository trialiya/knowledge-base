package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.script.SavedScript;
import io.github.trialiya.kb.model.script.ScriptParam;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a project's script manifest is read as — and, mostly, what a broken one costs.
 *
 * <p>The load-bearing claim here is the blast radius: the manifest is repository content written by
 * hand, so one bad entry has to cost that entry. A parser that refuses the file would let a single
 * typo take away every script the repository has, at the moment the model reaches for one.
 */
class ScriptManifestReaderTest {

    private static final String WHERE = "test manifest";

    @Test
    void readsEveryFieldOfAnEntry() {
        List<SavedScript> scripts =
                ScriptManifestReader.parse(
                        """
                        scripts:
                          - name: locale-diff
                            file: frontend/scripts/locale-diff.js
                            desc: Keys in en and not in ru
                            params:
                              - { name: area, desc: 'A subtree' }
                              - { name: limit, type: number, default: 50 }
                              - { name: since, required: true }
                          - name: bump-copyright
                            file: scripts/bump.js
                            desc: Put this year in headers
                            write: true
                            timeout: 25s
                        """,
                        WHERE);

        assertThat(scripts)
                .extracting(SavedScript::name)
                .containsExactly("locale-diff", "bump-copyright");
        SavedScript first = scripts.getFirst();
        assertThat(first.file()).isEqualTo("frontend/scripts/locale-diff.js");
        assertThat(first.desc()).isEqualTo("Keys in en and not in ru");
        assertThat(first.write()).isFalse();
        assertThat(first.timeout()).isNull();
        assertThat(first.params())
                .extracting(
                        ScriptParam::name,
                        ScriptParam::type,
                        ScriptParam::required,
                        ScriptParam::defaultValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "area", ScriptParam.Type.STRING, false, null),
                        org.assertj.core.groups.Tuple.tuple(
                                "limit", ScriptParam.Type.NUMBER, false, 50),
                        org.assertj.core.groups.Tuple.tuple(
                                "since", ScriptParam.Type.STRING, true, null));
        assertThat(scripts.get(1).write()).isTrue();
        assertThat(scripts.get(1).timeout()).isEqualTo(Duration.ofSeconds(25));
    }

    /**
     * One entry's mistake never reaches its neighbours — the whole reason this parser is lenient.
     */
    @Test
    void aBadEntryCostsOnlyItself() {
        List<SavedScript> scripts =
                ScriptManifestReader.parse(
                        """
                        scripts:
                          - name: fine
                            file: a.js
                            desc: Works
                          - name: no-desc
                            file: b.js
                          - name: UPPER
                            file: c.js
                            desc: Bad name
                          - name: strange
                            file: d.js
                            desc: Bad field
                            description: and another one
                          - name: fine
                            file: e.js
                            desc: Duplicate name
                          - name: also-fine
                            file: f.js
                            desc: Works too
                        """,
                        WHERE);

        assertThat(scripts).extracting(SavedScript::name).containsExactly("fine", "also-fine");
    }

    /**
     * {@code required} and a default together is a contradiction, not a stricter declaration: the
     * default could only ever be reached through the refusal the flag promises.
     */
    @Test
    void aRequiredParamWithADefaultIsTheEntrysError() {
        List<SavedScript> scripts =
                ScriptManifestReader.parse(
                        """
                        scripts:
                          - name: contradictory
                            file: a.js
                            desc: Both at once
                            params:
                              - { name: since, required: true, default: 2020-01-01 }
                        """,
                        WHERE);

        assertThat(scripts).isEmpty();
    }

    /** A name is a name, and {@code attachment:} is how an attachment will be addressed. */
    @Test
    void aNameCannotLookLikeAnAttachmentReference() {
        List<SavedScript> scripts =
                ScriptManifestReader.parse(
                        """
                        scripts:
                          - name: 'attachment:12'
                            file: a.js
                            desc: Not a script name
                        """,
                        WHERE);

        assertThat(scripts).isEmpty();
    }

    @Test
    void aFileThatIsNotAManifestIsAnEmptyList() {
        assertThat(ScriptManifestReader.parse("just some text", WHERE)).isEmpty();
        assertThat(ScriptManifestReader.parse("", WHERE)).isEmpty();
        assertThat(ScriptManifestReader.parse("scripts: 5", WHERE)).isEmpty();
        assertThat(ScriptManifestReader.parse("other: [1, 2]", WHERE)).isEmpty();
        assertThat(ScriptManifestReader.parse("scripts:\n  - [oops]\n", WHERE)).isEmpty();
        assertThat(ScriptManifestReader.parse("scripts: [ unclosed", WHERE)).isEmpty();
    }

    /** The manifest is repository content: it must not be able to name Java classes to build. */
    @Test
    void yamlTagsCannotConstructObjects() {
        assertThat(ScriptManifestReader.parse("!!java.io.File [/etc/passwd]", WHERE)).isEmpty();
    }

    @Test
    void aBadTimeoutOrWriteFlagIsTheEntrysError() {
        assertThat(
                        ScriptManifestReader.parse(
                                """
                                scripts:
                                  - name: a
                                    file: a.js
                                    desc: Bad timeout
                                    timeout: soon
                                  - name: b
                                    file: b.js
                                    desc: Bad write flag
                                    write: yes-please
                                """,
                                WHERE))
                .isEmpty();
    }
}
