/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * Decodes concatenated gzip members in a loop, checking headers and trailers as {@link java.util.zip.GZIPInputStream}
 * does.
 */
final class GzipMembers {

    private static final int GZIP_MAGIC = 0x8b1f;
    private static final int DEFLATE_METHOD = 8;
    private static final int FHCRC = 2;
    private static final int FEXTRA = 4;
    private static final int FNAME = 8;
    private static final int FCOMMENT = 16;
    /** MTIME (4), XFL (1) and OS (1). */
    private static final int FIXED_HEADER_FIELDS = 6;
    private static final int BUFFER_SIZE = 8192;

    private final byte[] payload;
    private final int maxLength;
    private final String algorithm;
    private final ByteArrayOutputStream output;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private final CRC32 crc = new CRC32();
    private final Inflater inflater = new Inflater(true);
    private int position;

    private GzipMembers(byte[] payload, int maxLength, String algorithm) {
        this.payload = payload;
        this.maxLength = maxLength;
        this.algorithm = algorithm;
        this.output = new ByteArrayOutputStream(Math.min(maxLength, BUFFER_SIZE));
    }

    /**
     * Decodes every member of {@code payload}, verifying each header and trailer, and fails as soon as the members
     * together yield more than {@code maxLength} bytes (the rule {@link BoundedReads#readAtMost} applies to streams).
     */
    static byte[] inflate(byte[] payload, int maxLength, String algorithm) throws IOException {
        GzipMembers members = new GzipMembers(payload, maxLength, algorithm);
        try {
            return members.inflateAll();
        } finally {
            members.inflater.end();
        }
    }

    private byte[] inflateAll() throws IOException {
        readHeader();
        do {
            inflateMember();
            readTrailer();
        } while (readNextHeader());
        return output.toByteArray();
    }

    private void inflateMember() throws IOException {
        inflater.reset();
        crc.reset();
        inflater.setInput(payload, position, payload.length - position);
        while (!inflater.finished()) {
            int read = inflateOnce();
            if (read > maxLength - output.size()) {
                throw DecompressedPayloadTooLargeException.exceeds(algorithm, maxLength);
            }
            output.write(buffer, 0, read);
            crc.update(buffer, 0, read);
        }
        // The inflater was given the rest of the payload, so the deflate stream ended where its unread bytes begin.
        position = payload.length - inflater.getRemaining();
    }

    private int inflateOnce() throws IOException {
        int read;
        try {
            read = inflater.inflate(buffer);
        } catch (DataFormatException e) {
            throw new ZipException(e.getMessage());
        }
        // Raw deflate has no preset dictionary, so no output before the end means missing input; anything else is
        // refused rather than retried forever.
        if (read == 0 && !inflater.finished()) {
            throw inflater.needsInput()
                    ? new EOFException("Unexpected end of ZLIB input stream")
                    : new ZipException("Inflater stopped without output or end of stream");
        }
        return read;
    }

    private void readTrailer() throws IOException {
        // Left to right, as in GZIPInputStream: a wrong CRC is reported before a missing ISIZE.
        if (readUnsignedInt() != crc.getValue()
                || readUnsignedInt() != (inflater.getBytesWritten() & 0xffffffffL)) {
            throw new ZipException("Corrupt GZIP trailer");
        }
    }

    /**
     * Reads the next member's header, or returns false at the end of the payload. Like {@code GZIPInputStream}, a
     * malformed or truncated next header ends the stream and what follows it is ignored, so messages it decoded still
     * decode the same. As in the Corretto 25.0.1 source, any bytes left are tried as a header. The Corretto 17.0.19
     * and 21.0.11 sources skip that when 18 bytes or fewer are left and their 512-byte buffer already holds them, so
     * whether they ignored a short header-shaped tail depended on where it fell; here a header followed by truncated
     * data or trailer is always refused.
     */
    private boolean readNextHeader() {
        if (position == payload.length) {
            return false;
        }
        try {
            readHeader();
            return true;
        } catch (EOFException | ZipException e) {
            return false;
        }
    }

    private void readHeader() throws EOFException, ZipException {
        int start = position;
        if (readUnsignedShort() != GZIP_MAGIC) {
            throw new ZipException("Not in GZIP format");
        }
        if (readUnsignedByte() != DEFLATE_METHOD) {
            throw new ZipException("Unsupported compression method");
        }
        int flags = readUnsignedByte();
        skip(FIXED_HEADER_FIELDS);
        if ((flags & FEXTRA) == FEXTRA) {
            skip(readUnsignedShort());
        }
        if ((flags & FNAME) == FNAME) {
            skipZeroTerminated();
        }
        if ((flags & FCOMMENT) == FCOMMENT) {
            skipZeroTerminated();
        }
        if ((flags & FHCRC) == FHCRC) {
            crc.reset();
            crc.update(payload, start, position - start);
            if (readUnsignedShort() != (int) (crc.getValue() & 0xffff)) {
                throw new ZipException("Corrupt GZIP header");
            }
        }
    }

    private void skipZeroTerminated() throws EOFException {
        while (readUnsignedByte() != 0) {
            // skips the field up to and including its terminator
        }
    }

    private void skip(int count) throws EOFException {
        if (count > payload.length - position) {
            throw new EOFException();
        }
        position += count;
    }

    private int readUnsignedByte() throws EOFException {
        if (position >= payload.length) {
            throw new EOFException();
        }
        return payload[position++] & 0xff;
    }

    private int readUnsignedShort() throws EOFException {
        return readUnsignedByte() | readUnsignedByte() << 8;
    }

    private long readUnsignedInt() throws EOFException {
        return readUnsignedShort() | (long) readUnsignedShort() << 16;
    }
}
