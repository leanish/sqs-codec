# Known limitations

Limitations and evidence gaps that remained when each release shipped, with their impact. Planned fixes are noted where
agreed.

## 0.6.0

Reviewed: the full change from `v0.5.1` to `v0.6.0-rc.6`, through six candidates. `docs/validation.md` lists the
reviews and real runs. 0.6.0 is rc.6 with only release metadata and a CI configuration file changed. These remained.
Entries marked "Planned for 0.6.1" have an agreed fix.

### Behavior

- **zstd retry exhaustion reads as a measured size.** Planned for 0.6.1.
  - When zstd reports that the one-step retry's largest permitted array is too small, `DecompressedPayloadTooLargeException`
    says the payload "exceeds" that array ceiling, which can be below the configured limit.
  - The class Javadoc says this case doesn't establish the decoded size, and neither does the diagnostic, but the
    message reads as a measurement.
  - A corrupt frame with a large declared window can produce it. That was traced from source, not probed.
  - Raising the limit for it can make the retry allocate more or change which exception is reported, while the
    malformed frame is still refused. Nothing is decoded wrongly.
  - Fix: a message specific to retry exhaustion, and a test that tells it apart from a measured streaming overrun.
- **One-pass zstd compression.**
  - **Extra output buffer:** compression holds an output array about the size of the raw payload, even when the result
    is small. The CHANGELOG states it, and it's kept as the cost of frames with small windows.
  - **Input ceiling:** from source, zstd-jni's worst-case output bound exceeds `Integer.MAX_VALUE` from 2,139,127,681
    input bytes, so `compress` fails before compressing. Allocation can fail sooner. That affects direct `compress`
    callers, and interceptors configured with a limit near 2 GiB. 0.5.x streamed into fixed-size chunks.
- **zstd limits over 1 GiB on zstd-jni's 32-bit native builds:** traced from source, not run.
  - There, a configured `maxDecodedPayloadBytes` above 1 GiB asks for a window log of 31, which the native library caps
    at 30, so every zstd decode fails, even of a small frame. The default 16 MiB limit is unaffected.
  - Several tests decode small fixtures with `Integer.MAX_VALUE`, so they'd fail on such a build too.
  - Recorded on the owner's call, with no fix planned.
- **Memory under the decode limit.** The limit bounds decoded bytes, not total heap or native memory. A sufficiently
  high limit also lets zstd's buffered streaming accept windows above the 0.5.1 decoder's 128 MiB default. Actual
  memory use depends on the frame and the decoder path.
- **The fresh-send size check runs after the UTF-8 conversion.** Planned for 0.6.1. For a fresh send configured to compress or encode, the
  check runs after `String.getBytes(UTF_8)` and before that entry's payload is processed. It applies even when the body
  would have been sent uncompressed. So the conversion of a huge body can exhaust the heap before the typed refusal, and
  earlier entries of a batch may already have been processed. 0.5.x converted the body the same way. A UTF-16 length
  pre-check could refuse definitely oversized bodies first, as long as it doesn't report the character count as a byte
  count.
- **Unpaired UTF-16 surrogates in fresh sends** are replaced during the UTF-8 conversion, as in 0.5.x. Any checksum or
  `l` the interceptor writes describes the replaced bytes, so they pass the receive side's UTF-8 check.
- **Non-canonical spellings of `v=1`** (`01`, `+1`), which 0.5.x accepted, are refused. The 0.5.x interceptor wrote
  canonical `1` when it created metadata, but it forwarded other spellings unchanged, and other encoders can produce
  them. Such messages in retained or relayed traffic now fail their receive.
- **A gzip member followed by a malformed member header.** On the reviewed JDKs, `GZIPInputStream` can treat the
  malformed header as the end of the stream after a valid member, as in 0.5.x. That prefix reaches the application if
  it passes the remaining receive checks: the applicable length and checksum checks, the decoded-byte limit and UTF-8
  validation.
- **A decoded SDK message keeps its wire metadata.** Only `Message.body()` is replaced, as in 0.5.x, so `md5OfBody` and
  `x-codec-meta` still describe the encoded body. Resending the decoded body with the old codec attributes can fail
  validation, or trigger another decode that changes the delivered body. A relay has two safe options. It can forward
  the original encoded body and attributes together, as the README says. Or it can send the decoded body as a fresh
  message without the old codec metadata.
- **ASCII85 output size:** encoding a very large input directly can overflow the encoder's output-size arithmetic.

### Build and release

- **PIT ignores `-PsqsCodec.testRuntimeJdkVersion`.** Planned for 0.6.1. Since the move to java-conventions 0.7.0, the property sets the
  launcher for `Test` tasks only.
  - The PIT task extends `JavaExec` and uses the conventions' default JDK. So a combined `test pitest` run with the
    property can run tests on one JDK and mutation testing on another.
  - No workflow combines them: the legacy-JDK workflow runs `test` only.
  - The CHANGELOG's "doesn't have JavaExec tasks" is wrong, since the PIT task is one.
- **A SNAPSHOT dispatch can replace a pending release publish.** Planned for 0.6.1. The publishing workflow's concurrency group is shared
  by all refs, and GitHub keeps one pending run per group.
  - With one publish running and a release publish pending, a third dispatch replaces the pending one.
  - The replaced run uploaded nothing and can be dispatched again.
  - Fix: `queue: max`. Until then, don't dispatch a SNAPSHOT while a release publish is pending.
- **The supply-chain checks had no merge veto.** In the rulesets inspected for 0.6.0, `Build` and `PIT` were required
  and CodeQL was enforced. `supply-chain / supply-chain` and `supply-chain / cooldown` weren't required, though the
  gate's documentation recommends requiring both. 0.5.1 had no supply-chain workflow, so nothing existing was weakened.
- **The CHANGELOG omits the runner and action-pin changes.** The README names the runner pin. Recorded as a
  documentation preference.

### Tests and evidence

- **Test gaps left open:**
  - The decode-side over-limit tests show rejection. Where decoding stops, and the allocation bounds, were traced from
    source only.
  - The gzip `l` tests check parsing, without all the former reserialization assertions.
  - The fresh-send limit matrix covers gzip, zstd and both encodings, but not snappy or zstd `MAXIMUM`.
  - The malformed UTF-8 tests don't cover surrogate code points, overlong encodings or out-of-range sequences.
  - Two receive cases aren't tested: snappy exactly at the configured limit, and several decoded messages that each fit
    the limit but together exceed it. The existing multi-message test uses one encoded and one plain message.
- **What the runs didn't establish:**
  - Async receive was checked against the SDK 2.57.13 source. Client runs used sync receive, and an SDK probe
    exercised sync and async send refusal.
  - The compatibility harness logs observations for selected ABI calls and queue reads rather than asserting all of
    them.
  - LocalStack exercised standard queues only. Live AWS and FIFO delivery weren't exercised.
  - A dispatch from a wrong ref wasn't run.

### Documentation

These texts claim more than the code does and need narrower wording. Planned for 0.6.1:

- **Custom compressors:** the upgrade guide describes what happens when a caller uses the new
  `decompress(byte[], int)` on an old binary. It doesn't say that a class written for 0.5.x, with only `compress` and
  `decompress(byte[])`, must now supply or inherit the bounded overload to compile against 0.6.0.
- **zstd window:** the window limit applies when zstd uses buffered streaming. Native zstd can decode some complete
  frames straight into the output buffer without checking it. "Whatever window the frames declare" is too broad for the
  one-step retry, which native frame validation still applies to.
- **"Gigabytes" for gzip:** within SQS's 1 MiB body limit, a gzip payload can't inflate to several gigabytes. The point
  stands that 0.5.x had no limit and could exhaust a consumer's heap.
- **0.5.x zstd frames:** the CHANGELOG and a Javadoc comment describe them categorically. Non-empty frames can omit
  their content size and declare the level's window, but an empty payload's frame can carry its size.
- **0.7.0 migration:** the check before dropping the retry concerns frames whose decoded payload fits the consumer's
  limit. Producer and consumer limits need to stay aligned anyway, since a 0.6.0 producer with a higher limit can
  already write frames a lower-limit consumer refuses.
- **Snappy advisory:** the CHANGELOG's snappy-java entry conflates CVE-2026-90559's own advisory record with the
  repository advisory for the same issue.
- **Error handling:** the README says failures come as `CodecException` subtypes. A send without a body can fail with
  a `NullPointerException` in the fresh-send transformation instead.
- **Deprecated setter:** the README describes `withIncludeRawPayloadLength(boolean)` as if it wrote metadata. It has no
  effect. Fresh compression and both `forOutbound` overloads supply `l`, and a relay can keep a missing `l`.
- **Small corrections:**
  - The test name `fromAttributes_rawLengthIgnoredWithoutCompression` doesn't fit its gzip case, which keeps `l`.
  - A sentence in `ZstdCompressor.compress`'s Javadoc reads badly.
