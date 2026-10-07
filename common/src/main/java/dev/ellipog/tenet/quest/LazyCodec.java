package dev.ellipog.tenet.quest;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A codec that resolves the codec it stands for on first use.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Because the reward types and the reward table are <b>mutually</b> recursive, and Java initialises
 * classes on first touch. The loop is: {@code RewardTable.Entry.CODEC} needs the reward dispatch codec,
 * the dispatch codec is built from the type list, a table reward's codec embeds a table's codec, and a
 * table's codec is built from its entries' codec. Reading any one of them first runs the whole loop, and
 * whichever one was touched first is then read <i>while still initialising</i> — a field that is still
 * {@code null}. Which link fails depends on which class the game happens to touch first, so the fault is
 * invisible in one run and a wall of {@code ExceptionInInitializerError}s in the next.
 *
 * <p>One link has to be lazy, and the honest place is the table reward's {@code inline} field: it stands
 * for "this reward carries a table", which is a fact about a file rather than something the registry needs
 * in order to exist. Building that field through this class means a table reward's codec can be created
 * while the table's own class is still initialising, and the delegate is read the first time a file is
 * actually read or written — by which point every class involved has finished.
 *
 * <p>Only the three methods a {@link Codec} is asked for in practice are forwarded; the rest of the
 * fluent API ({@code listOf}, {@code fieldOf}, …) belongs to the wrapper and never reaches the delegate,
 * which is the point — no combinator can force the delegate into existence early.
 */
public final class LazyCodec<A> implements Codec<A> {

    private final Supplier<Codec<A>> source;
    private volatile Codec<A> delegate;

    private LazyCodec(Supplier<Codec<A>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** The codec {@code source} answers with, read once. */
    public static <A> Codec<A> of(Supplier<Codec<A>> source) {
        return new LazyCodec<>(source);
    }

    @Override
    public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
        return delegate().decode(ops, input);
    }

    @Override
    public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
        return delegate().encode(input, ops, prefix);
    }

    /**
     * The delegate's own description, so an error message that names this codec names the real one.
     *
     * <p>Reading it resolves the delegate, which is deliberate: a description is only ever wanted once
     * something is encoding or decoding, and a codec that described itself as "lazy" would make every
     * data error harder to read than the fault it is reporting.
     */
    @Override
    public String toString() {
        return delegate().toString();
    }

    private Codec<A> delegate() {
        Codec<A> resolved = delegate;
        if (resolved == null) {
            resolved = source.get();
            delegate = resolved;
        }
        return resolved;
    }
}
