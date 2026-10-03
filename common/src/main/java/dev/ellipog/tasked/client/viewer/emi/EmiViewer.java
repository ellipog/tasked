package dev.ellipog.tasked.client.viewer.emi;

import dev.ellipog.tasked.client.viewer.Integrations;

/**
 * The EMI holder: the probe id and the one instance, so that naming EMI's classes happens in
 * exactly one file and never before EMI is known to be there.
 *
 * <p>The three-part arrangement is the one a soft adapter needs: {@link #MOD_ID} is a
 * compile-time constant, so reading it inlines the string and loads nothing; {@link #INSTANCE} is
 * declared as {@link Integrations.ViewerAdapter}, so resolving the field needs no EMI type; and its
 * initialiser runs only when the field is first read, which is inside the load check. Loading this
 * class therefore cannot fail on a client without EMI — loading {@link TaskedEmiPlugin} could,
 * which is why nothing does it earlier.
 */
public final class EmiViewer {

    /** EMI's mod id, for the load check. A constant on purpose: callers probe with it. */
    public static final String MOD_ID = "emi";

    /** Built on first read, behind the check. See the class comment. */
    public static final Integrations.ViewerAdapter INSTANCE = new TaskedEmiPlugin();

    private EmiViewer() {
    }
}
