package dev.ellipog.tasked.client.dev;

import java.util.Collection;

/**
 * What a chapter or group id may be, checked on the client before the server is asked.
 *
 * <h2>Why this is not {@code Checks.id}</h2>
 *
 * <p>Because the loader's rule and the book's rule are not the same rule. {@code Checks.id} asks "could
 * this be an id", and it permits a leading underscore — an id beginning {@code _} is a perfectly good
 * string that every folder the loader walks will <b>skip</b>. On disk that is an id nobody can ever read
 * back: the chapter would exist, load as nothing, and be invisible in the very list that just made it.
 * So the extra rules here are the ones the filesystem adds, and they are stated where the card can show
 * them as the author types rather than where a refusal arrives after the press.
 *
 * <p>The collision check is advisory in the same way everything a client checks is: the server refuses a
 * duplicate too, from the loader's own index. This exists so the author does not have to find that out by
 * sending the edit.
 */
public final class ChapterNaming {

    /** How long an id may be, matching the loader's own limit. */
    public static final int MAX_LENGTH = 64;

    /**
     * Why this id cannot be used, or null when it can.
     *
     * @param id       what the author typed
     * @param existing every id the tree already uses <i>of the same kind</i>, ids and aliases
     */
    public static String problemWith(String id, Collection<String> existing) {
        if (id == null || id.isBlank()) {
            return "an id is needed - it becomes the folder's name";
        }
        if (id.length() > MAX_LENGTH) {
            return "an id may be at most " + MAX_LENGTH + " characters";
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return "only a-z, 0-9 and _ are allowed - '" + c + "' is not";
            }
        }
        if (id.startsWith("_")) {
            return "an id may not begin with _ - the loader skips every name beginning with it";
        }
        if (id.endsWith(".deleted")) {
            return "an id may not end with .deleted - that is what a removed chapter is renamed to";
        }
        if (existing != null && existing.contains(id)) {
            return "\"" + id + "\" is already used - ids and aliases have to be unique";
        }
        return null;
    }

    /**
     * The first free {@code base + suffix}, {@code base + suffix 2}, ... not in {@code taken}.
     *
     * <p>What a duplicate is pre-filled with, so the card opens on a name that will work rather than on
     * one the author has to fix before the button does anything.
     */
    public static String suggested(String base, String suffix, Collection<String> taken) {
        String candidate = base + suffix;
        int n = 2;
        while (taken != null && taken.contains(candidate)) {
            candidate = base + suffix + n++;
        }
        return candidate;
    }

    private ChapterNaming() {
    }
}
