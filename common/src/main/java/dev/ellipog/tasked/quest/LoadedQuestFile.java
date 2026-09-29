package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.JsonDocument;

import java.nio.file.Path;

/**
 * One quest file that parsed, validated and decoded.
 *
 * <p>The document is kept alongside the decoded tree rather than thrown away, so that anything done
 * later — cross-file checks, the editor, {@code /tasked quest <id>} — can still point at a line and
 * column. Losing the positions once decoding succeeds would mean every later complaint degrades to
 * "somewhere in this file", which is exactly the problem the parser exists to solve.
 */
public record LoadedQuestFile(Path path, String displayName, JsonDocument document, QuestFile file) {
}
