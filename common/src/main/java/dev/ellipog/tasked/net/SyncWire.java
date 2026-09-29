package dev.ellipog.tasked.net;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Packing a JSON sync message into network bytes: compress it, cut it into chunks, and put it back
 * together on the other side.
 *
 * <h2>Why this is not just "send the string"</h2>
 *
 * <p>Both directions of this class exist because of one measured limit and one unmeasured risk.
 *
 * <p>The <b>measured</b> one: {@code ClientboundCustomPayloadPacket} carries
 * {@code MAX_PAYLOAD_SIZE = 1048576}, and the value is used only as the fallback codec for an
 * <i>unknown</i> payload id — so a registered payload is not held to it by the compiler or by
 * vanilla. That is worse than a hard limit, not better. There is no point at which exceeding it
 * produces an error naming the size; what happens is the connection rejects or stalls a packet
 * nobody can see the shape of. The plan called this out from Stage 4's first draft — "a large pack
 * will not fit in a single packet" — and it is the reason chunking is here before the pack that
 * needs it rather than after.
 *
 * <p>The <b>unmeasured</b> one: a deflate stream can expand without bound. A few kilobytes of
 * crafted input inflates to gigabytes, and an inflater with no cap will happily try to allocate it.
 * The bytes come from the server rather than from a stranger, which is not the same as being safe:
 * a server running a malicious or broken pack would be able to exhaust a client's memory, and the
 * client has no way to refuse after the fact. So {@link #inflate} takes a ceiling and enforces it
 * <i>while</i> inflating rather than afterwards, because by then the allocation has happened.
 *
 * <h2>Compression is skipped when it does not pay</h2>
 *
 * <p>{@link #pack} compresses and then compares: if the deflated bytes are not smaller, the raw
 * bytes go instead, behind a one-byte flag. On quest JSON — which is mostly repeated keys, and
 * compresses about ten to one — that branch will essentially never be taken. It exists because the
 * alternative is a code path that is never exercised in the case it was written for, and because a
 * tiny delta message compressing to something larger is a real possibility rather than a
 * hypothetical one.
 *
 * <h2>The flag byte travels inside the payload, not beside it</h2>
 *
 * <p>Deliberately. The alternative is a boolean on the payload record, which costs a field
 * component on a {@code StreamCodec.composite} that has a hard ceiling of six — and it would have
 * to be repeated on every chunk of one message, where the only thing that can happen is that they
 * disagree. As a header byte it is part of the bytes being chunked, so it cannot disagree with
 * itself.
 *
 * <h2>Chunking, and what "refuse" means here</h2>
 *
 * <p>A chunk is 24 KB of packed bytes. Chunks are keyed by index within a transfer, so they may
 * arrive in any order, and a duplicate index replaces rather than double-counts — a resend is a
 * normal thing for a reliable transport to do at the layer below.
 *
 * <p>A transfer whose chunks disagree about how many there are is <b>dropped whole</b>, not
 * half-kept. A half-assembled message would be JSON that parses in the middle and throws at the
 * end, which is the sort of failure that reads as a serialiser bug rather than as a truncated
 * packet. Dropping it costs a re-sync; keeping it costs an hour.
 */
public final class SyncWire {

    /**
     * How many bytes of packed data go in one chunk.
     *
     * <p>24 KB, which is a compromise rather than a measurement. Small enough that a chunk is
     * comfortably inside any limit a loader or a proxy might impose, and far inside vanilla's 1 MiB
     * fallback ceiling; large enough that a 300 KB compressed tree is thirteen packets rather than
     * three hundred. The plan says "chunked and compressed" without a size, so this is the number
     * chosen and the reasoning behind it rather than a figure imported from somewhere.
     */
    public static final int CHUNK_BYTES = 24 * 1024;

    /**
     * The most chunks one message may be split into.
     *
     * <p>A cap on the sender, and the reason it is a cap at all: it bounds what a receiver will
     * assemble, so a chunk count arriving off the wire cannot ask for an allocation larger than
     * this. 512 chunks of 24 KB is 12 MB of packed data, which is several times the largest quest
     * pack that could plausibly exist and still far short of a memory problem.
     */
    public static final int MAX_CHUNKS = 512;

    /**
     * The most bytes {@link #inflate} will produce.
     *
     * <p>8 MB. The arithmetic: a quest of average size serialises to roughly 600 bytes of JSON, so
     * 8 MB is about thirteen thousand quests — comfortably past any pack anyone has written, and
     * the sort of ceiling that will not need revisiting. It is here to bound a decompression bomb,
     * which is bounded by the <i>ratio</i> rather than by the input, so a limit on the input would
     * not have helped.
     */
    public static final int MAX_INFLATED_BYTES = 8 * 1024 * 1024;

    /** How many half-received messages a receiver will hold at once before dropping the oldest. */
    public static final int MAX_PENDING_TRANSFERS = 4;

    /** The header byte meaning "these bytes are deflate data". */
    private static final byte FLAG_DEFLATE = 1;

    /** The header byte meaning "these bytes are exactly what the sender had". */
    private static final byte FLAG_RAW = 0;

    private static final AtomicInteger NEXT_TRANSFER = new AtomicInteger();

    private SyncWire() {
    }

    /**
     * A transfer id for a new message.
     *
     * <p>A counter rather than a UUID: transfers are scoped to one connection, the client forgets
     * everything on disconnect, and a receiver keys pending transfers by this number — so the only
     * property that matters is that two live transfers never share one, which a counter gives for
     * three bytes on the wire instead of sixteen.
     *
     * <p>Wraps. It is an {@code int} and there is nothing to do about that: after two billion sends
     * a transfer id could in principle collide with a still-pending one, and a still-pending
     * transfer that has survived two billion sends is one that will never complete anyway.
     */
    public static int newTransferId() {
        return NEXT_TRANSFER.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // Compression
    // ------------------------------------------------------------------

    /**
     * The bytes to put on the wire: a flag byte, then either the raw data or its deflate stream.
     *
     * <p>Uses {@code BEST_SPEED} rather than {@code BEST_COMPRESSION}. This is a game packet: the
     * difference between the two on JSON of this shape is a few per cent, and the difference in
     * latency is paid on the server thread every time a quest moves. Bandwidth is not the scarce
     * resource here, and the plan's concern was fitting in a packet rather than saving bytes.
     */
    public static byte[] pack(byte[] raw) {
        byte[] compressed = deflate(raw);
        if (compressed.length >= raw.length) {
            // Compressing made it bigger, which a short message can. Send it as it is, so a delta
            // that is already tiny does not pay for a header and a compressor's own framing.
            byte[] out = new byte[raw.length + 1];
            out[0] = FLAG_RAW;
            System.arraycopy(raw, 0, out, 1, raw.length);
            return out;
        }
        byte[] out = new byte[compressed.length + 1];
        out[0] = FLAG_DEFLATE;
        System.arraycopy(compressed, 0, out, 1, compressed.length);
        return out;
    }

    /**
     * Undoes {@link #pack}, refusing to produce more than
     * {@link #MAX_INFLATED_BYTES}.
     *
     * @throws MalformedSync if the flag byte is not one this version writes, the stream is truncated,
     *                       the data is not deflate data at all, or it expands past the ceiling
     */
    public static byte[] unpack(byte[] packed) {
        if (packed.length == 0) {
            throw new MalformedSync("an empty sync message -- there is not even a flag byte");
        }
        byte flag = packed[0];
        byte[] body = new byte[packed.length - 1];
        System.arraycopy(packed, 1, body, 0, body.length);

        if (flag == FLAG_RAW) {
            if (body.length > MAX_INFLATED_BYTES) {
                throw new MalformedSync("a raw sync message of " + body.length + " bytes is past the "
                        + MAX_INFLATED_BYTES + "-byte ceiling");
            }
            return body;
        }
        if (flag == FLAG_DEFLATE) {
            return inflate(body, MAX_INFLATED_BYTES);
        }
        // Not a version mismatch to tolerate: a flag this build does not write means the sender is
        // not a build this one understands, and guessing at the body would be guessing at a format.
        throw new MalformedSync("unknown sync message flag " + flag
                + " -- this build writes 0 (raw) and 1 (deflate)");
    }

    /** Deflates, for {@link #pack} and for a test that wants a stream to hand to {@link #inflate}. */
    public static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int written = deflater.deflate(buffer);
                if (written == 0 && deflater.needsInput()) {
                    // Cannot happen after finish() with input set, but a loop whose exit depends on
                    // a compressor's internal state is a loop worth having a second way out of.
                    break;
                }
                out.write(buffer, 0, written);
            }
            return out.toByteArray();
        }
        finally {
            deflater.end();
        }
    }

    /**
     * Inflates, enforcing a ceiling as it goes.
     *
     * <h2>Why the ceiling is checked inside the loop</h2>
     *
     * <p>Checking the final size would mean the allocation had already happened, and the whole point
     * of the limit is to bound what is allocated. So the check is against what has been written so
     * far plus what is about to be, before it is written.
     *
     * <h2>Why a zero-length inflate is an exit rather than a retry</h2>
     *
     * <p>{@code Inflater.inflate} returns zero when it wants more input or a preset dictionary, and
     * neither is coming — the entire stream was handed over before the loop began. Retrying would
     * spin forever on a truncated message, which is a hang rather than an error, and a hang in a
     * payload handler takes the client with it.
     *
     * @param maxBytes the most bytes to produce. Throws past it rather than truncating: a truncated
     *                 JSON document does not parse, and a caller that silently got half a tree would
     *                 show half a questline.
     */
    public static byte[] inflate(byte[] deflated, int maxBytes) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(deflated);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(64 * 1024, Math.max(64, deflated.length * 4)));
            byte[] buffer = new byte[8192];

            while (!inflater.finished()) {
                int written;
                try {
                    written = inflater.inflate(buffer);
                }
                catch (DataFormatException e) {
                    throw new MalformedSync("the sync message is not deflate data: " + e.getMessage(), e);
                }
                if (written == 0) {
                    break;
                }
                if (out.size() + written > maxBytes) {
                    throw new MalformedSync("the sync message inflates past " + maxBytes
                            + " bytes, which this client will not allocate for a packet");
                }
                out.write(buffer, 0, written);
            }

            if (!inflater.finished()) {
                throw new MalformedSync("the sync message is a truncated deflate stream: "
                        + inflater.getRemaining() + " byte(s) left unread");
            }
            return out.toByteArray();
        }
        finally {
            inflater.end();
        }
    }

    // ------------------------------------------------------------------
    // Chunking
    // ------------------------------------------------------------------

    /**
     * Cuts packed bytes into chunks of at most {@link #CHUNK_BYTES}.
     *
     * <p>An empty message still produces one chunk, not zero. Zero would mean a transfer that never
     * completes, and the empty case is not hypothetical: an empty questline still sends a tree, so
     * the client can tell "nothing loaded" from "nothing received".
     */
    public static List<byte[]> chunk(byte[] packed) {
        if (packed.length > (long) CHUNK_BYTES * MAX_CHUNKS) {
            throw new IllegalArgumentException("a sync message of " + packed.length
                    + " bytes needs more than " + MAX_CHUNKS + " chunks, which the receiver will not "
                    + "assemble. Raise MAX_CHUNKS deliberately or make the message smaller.");
        }

        List<byte[]> chunks = new ArrayList<>();
        if (packed.length == 0) {
            chunks.add(new byte[0]);
            return chunks;
        }
        for (int start = 0; start < packed.length; start += CHUNK_BYTES) {
            int length = Math.min(CHUNK_BYTES, packed.length - start);
            byte[] part = new byte[length];
            System.arraycopy(packed, start, part, 0, length);
            chunks.add(part);
        }
        return chunks;
    }

    /**
     * Puts chunks back together, keyed by transfer id.
     *
     * <h2>One receiver per direction, held by whoever handles the payload</h2>
     *
     * <p>Stateful on purpose: a chunk arriving out of order has to be remembered until the ones
     * before it arrive, and there is nowhere else for that memory to live. So this is created once
     * per payload type at the point the handler is registered, not per message.
     *
     * <h2>Bounded, because the other end of the wire chooses the keys</h2>
     *
     * <p>Pending transfers are capped, and the oldest is dropped when the cap is reached. Without
     * that, a sender that starts transfers and never finishes them — a bug, or a client that
     * disconnects mid-message and comes back — grows this map forever, and the growth is invisible
     * until memory runs out.
     */
    public static final class Reassembler {

        private record Pending(byte[][] parts, int count, boolean full, int received) {
        }

        private final Map<Integer, Pending> pending = new LinkedHashMap<>();

        private int completed;
        private int droppedMismatched;
        private int droppedEvicted;

        /**
         * Offers one chunk.
         *
         * @return the whole packed message when this chunk completed it, otherwise null
         * @throws MalformedSync if the chunk's own header is impossible — a count of zero, a count
         *                       past {@link #MAX_CHUNKS}, or an index outside the count. These are
         *                       refused rather than dropped, because a chunk that cannot be placed
         *                       means the sender and receiver disagree about the format, and that is
         *                       worth a log line rather than a silent wait for chunks that will never
         *                       come.
         */
        public byte[] accept(SyncChunk chunk, byte[] data) {
            if (chunk.count() < 1 || chunk.count() > MAX_CHUNKS) {
                throw new MalformedSync("a chunk claims to be 1 of " + chunk.count()
                        + ", which is outside 1.." + MAX_CHUNKS);
            }
            if (chunk.index() < 0 || chunk.index() >= chunk.count()) {
                throw new MalformedSync("chunk " + chunk.index() + " is outside a message of "
                        + chunk.count() + " chunk(s)");
            }

            Pending current = pending.get(chunk.transferId());
            if (current == null || current.count() != chunk.count()) {
                if (current != null) {
                    // The same transfer id with a different chunk count. One of the two is wrong and
                    // there is no way to tell which, so neither is trusted -- see the class note on
                    // dropping a transfer whole.
                    pending.remove(chunk.transferId());
                    droppedMismatched++;
                }
                if (pending.size() >= MAX_PENDING_TRANSFERS) {
                    Integer oldest = pending.keySet().iterator().next();
                    pending.remove(oldest);
                    droppedEvicted++;
                }
                current = new Pending(new byte[chunk.count()][], chunk.count(), chunk.full(), 0);
                pending.put(chunk.transferId(), current);
            }
            else if (current.full() != chunk.full()) {
                // Same reason as the count: every chunk of one message carries the message's own
                // flag, so a disagreement is corruption rather than a difference of opinion.
                pending.remove(chunk.transferId());
                droppedMismatched++;
                return null;
            }

            if (current.parts()[chunk.index()] == null) {
                // A second copy of an index already held is a resend, and replacing it keeps the
                // count honest. Counting it twice would complete the message early with a hole in it.
                current.parts()[chunk.index()] = data;
                pending.put(chunk.transferId(), new Pending(current.parts(), current.count(),
                        current.full(), current.received() + 1));
            }
            else {
                current.parts()[chunk.index()] = data;
            }

            Pending now = pending.get(chunk.transferId());
            if (now.received() < now.count()) {
                return null;
            }

            int total = 0;
            for (byte[] part : now.parts()) {
                if (part == null) {
                    // Should be unreachable: received counts distinct indices. Checked anyway,
                    // because concatenating a null would be a NullPointerException from inside the
                    // assembler with nothing to say about which chunk was missing.
                    return null;
                }
                total += part.length;
            }
            if (total > CHUNK_BYTES * MAX_CHUNKS) {
                pending.remove(chunk.transferId());
                throw new MalformedSync("an assembled message of " + total + " bytes is past the "
                        + (CHUNK_BYTES * MAX_CHUNKS) + "-byte ceiling for one transfer");
            }

            byte[] whole = new byte[total];
            int at = 0;
            for (byte[] part : now.parts()) {
                System.arraycopy(part, 0, whole, at, part.length);
                at += part.length;
            }

            pending.remove(chunk.transferId());
            completed++;
            return whole;
        }

        /** Forgets a transfer, for a disconnect that leaves one half-received. */
        public void forgetAll() {
            pending.clear();
        }

        /** How many messages are half-received right now. */
        public int pendingTransfers() {
            return pending.size();
        }

        /** How many completed. Diagnostics for a log line, and for a test to assert it ran. */
        public int completed() {
            return completed;
        }

        /** How many were dropped for disagreeing with themselves. */
        public int droppedMismatched() {
            return droppedMismatched;
        }

        /** How many were dropped to stay inside {@link #MAX_PENDING_TRANSFERS}. */
        public int droppedEvicted() {
            return droppedEvicted;
        }
    }

    /**
     * A sync message that could not be read.
     *
     * <p>A named type rather than {@code IllegalArgumentException}, because the caller's response is
     * specific: log it, ignore the packet, and keep the connection. A malformed message is <b>not</b>
     * grounds for disconnecting — that would turn a server running a pack this client cannot parse
     * into a client that cannot join at all — and it is not grounds for a crash, because the failure
     * arrives from the network.
     */
    public static final class MalformedSync extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MalformedSync(String message) {
            super(message);
        }

        public MalformedSync(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
