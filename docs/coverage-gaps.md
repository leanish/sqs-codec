# Known limitations

Limitations found while reviewing each release and not fixed in it, with their impact. Unless an entry says otherwise,
it's planned for the next patch release.

## 0.6.0

Reviewed: the full change from `v0.5.1` to `v0.6.0-rc.6`, through six candidates. `docs/validation.md` lists the
reviews and real runs. 0.6.0 is rc.6 with only release metadata and a CI configuration file changed. These were found
and not fixed in it:

### Behavior

- **zstd retry exhaustion reads as a measured size.** When the one-step retry for legacy zstd frames can't fit the
  output in its largest permitted array, `DecompressedPayloadTooLargeException` says the payload "exceeds" the limit.
  The class Javadoc already says this case doesn't establish the decoded size. A corrupt frame with a large declared
  window can still produce this message, which could lead an operator to raise the limit for a message that won't
  decode anyway. The refusal itself is right, and nothing is decoded wrongly. Fix: a message specific to retry
  exhaustion, and a test that tells it apart from a measured streaming overrun.
- **One-pass zstd compression has an input ceiling just below 2 GiB.** From 2,139,127,681 input bytes, zstd-jni's
  worst-case output bound exceeds the largest Java array, so `compress` fails before compressing. 0.5.x streamed into
  fixed-size chunks and could compress such input. SQS bodies are far smaller, so this affects only direct `compress`
  callers with inputs that large. The CHANGELOG states the ceiling; the exact threshold is recorded here.
- **zstd limits over 1 GiB on zstd-jni's 32-bit native builds.** There, a configured `maxDecodedPayloadBytes` above
  1 GiB asks for a window log of 31, and the native library caps it at 30. Every zstd decode then fails, even of a
  small frame. The default 16 MiB limit is unaffected. Several tests decode small fixtures with `Integer.MAX_VALUE`, so
  they'd fail on such a build too.
- **The fresh-send size check converts the whole body first.** A fresh send that the interceptor compresses or encodes
  is refused when its UTF-8 bytes exceed the limit, but only after `String.getBytes(UTF_8)` has built the byte array.
  0.5.x built the same array. A cheap UTF-16 length pre-check could refuse definitely oversized bodies first; it must
  not report the character count as a byte count.
- **Unpaired UTF-16 surrogates in fresh sends** are replaced by `String.getBytes(UTF_8)`, as in 0.5.x, and the checksum
  and `l` describe the replaced bytes, so the receive side accepts them.
- **A gzip member followed by a malformed member header** is read as complete: `GZIPInputStream` treats the malformed
  header as the end of the stream, as in 0.5.x. A legacy message without a usable `l` or a checksum can therefore
  deliver only its first member.
- **A decoded SDK message keeps its wire metadata.** Only `Message.body()` is replaced, as in 0.5.x, so `md5OfBody`
  and `x-codec-meta` still describe the encoded body. The README's relay guidance (forward the original body and
  attributes) covers it.
- **The README's force-compression example** starts from the default interceptor, whose compression is `NONE`, so it
  doesn't compress. 0.5.1's README has the same example.

### Build and release

- **PIT ignores `-PsqsCodec.testRuntimeJdkVersion`.** Since the move to java-conventions 0.7.0, the property sets the
  launcher for `Test` tasks only. The PIT task extends `JavaExec` and uses the conventions' default JDK. A combined
  `test pitest` run with the property can run tests on one JDK and mutation testing on another. No workflow combines
  them: the legacy-JDK workflow runs `test` only. The CHANGELOG's "doesn't have JavaExec tasks" is also wrong, since
  the PIT task is one.
- **A SNAPSHOT dispatch can replace a pending release publish.** The publishing workflow's concurrency group is shared
  by all refs, and GitHub keeps one pending run per group. With one publish running and a release publish pending, a
  third dispatch replaces the pending one. The replaced run uploaded nothing and can be dispatched again. Fix:
  `queue: max`. Until then, don't dispatch a SNAPSHOT while a release publish is pending.
- **The supply-chain checks aren't required on `main`.** The rulesets require `Build` and `PIT` and enforce CodeQL, but
  not `supply-chain / supply-chain` or `supply-chain / cooldown`, which the gate's documentation recommends requiring.
  0.5.1 had no supply-chain workflow, so this weakens nothing that existed before.

### Documentation

These texts claim slightly more than the code does. None leads to a wrong action, but each needs narrower wording:

- **Custom compressors:** the upgrade guide describes what happens when a caller uses the new
  `decompress(byte[], int)` on an old binary. It doesn't say that a class written for 0.5.x, with only `compress` and
  `decompress(byte[])`, no longer compiles against 0.6.0.
- **zstd window:** the window limit applies when zstd uses buffered streaming. Native zstd can decode some complete
  frames straight into the output buffer without checking it. "Whatever window the frames declare" is too broad for the
  one-step retry, which native frame validation still applies to.
- **"Gigabytes" for gzip:** within SQS's 1 MiB body limit, a gzip payload can't inflate to several gigabytes. The point
  stands that 0.5.x had no limit and could exhaust a consumer's heap.
- **0.5.x zstd frames:** the CHANGELOG and a Javadoc comment describe them categorically. Non-empty frames can omit
  their content size and declare the level's window, but an empty payload's frame can carry its size.
- **0.7.0 migration:** the checks before 0.7.0 don't mention 0.6.0 writers configured with a higher limit than their
  consumers, which can also write frames those consumers refuse.
- **Snappy advisory:** the CHANGELOG's snappy-java entry conflates CVE-2026-90559's own advisory record with the
  repository advisory for the same issue.
- **Error handling:** the README says failures come as `CodecException` subtypes. A send without a body fails with a
  `NullPointerException` first.
- **Deprecated setter:** the README describes `withIncludeRawPayloadLength(boolean)` as if it wrote metadata. It has no
  effect; the interceptor and both `forOutbound` overloads write `l` whenever they compress.
- **Small corrections:** a test name (`fromAttributes_rawLengthIgnoredWithoutCompression` also covers a gzip case that
  keeps `l`), and a sentence in `ZstdCompressor.compress`'s Javadoc that reads badly.
