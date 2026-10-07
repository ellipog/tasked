package dev.ellipog.tenet.progress;

/**
 * What a bulk claim is allowed to touch — the rewards panel's filter chips, on the wire.
 *
 * <p>Sent rather than a client-side predicate, because the server must not take a client's word for
 * what it is owed. The filter only ever <b>narrows</b> the sweep: every reward it does reach is still
 * checked against the stored progress, so the worst a forged value can do is claim less than the
 * player asked for.
 */
public enum ClaimFilter {

    /** Everything outstanding. */
    ALL,

    /** Item rewards and the tables that roll them. */
    ITEMS,

    /** Choice rewards — nothing is granted; every picker is queued for the player to answer. */
    CHOICES;

    /** A wire ordinal, defaulting to {@link #ALL} for a value this build does not know. */
    public static ClaimFilter byOrdinal(int ordinal) {
        ClaimFilter[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ALL;
    }
}
