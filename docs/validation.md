# Release validation

How each release was checked before it shipped: its candidates, what each reviewer added, the rough cost per model and
the evidence from real runs. Known limitations are in `docs/coverage-gaps.md`.

Reviewers, by role:
- **Haiku:** Claude Haiku sub-agents, read-only, each checking one area: claims, a checklist, or gaps.
- **Sol:** Codex reviews. Sol A reviews correctness, Sol B scope and Sol C claims and docs, each in its own thread.
- **Astra:** Codex's most expensive model. It reviews last, challenging the earlier findings.

Claude Opus implemented the changes and settled every finding with the reviewer that raised it.

Token counts come from the review tools' own usage records. Codex input is mostly cached thread context, so its
uncached share is given too. No prices are applied. Claude Opus's own usage wasn't recorded per release.

## 0.6.0 - 2026-11-22

Six candidates, from `v0.5.1`. Tags are lightweight; the release is `v0.6.0` at `691c621`.

| Candidate | Commit | Date | Why the next candidate was cut |
| --- | --- | --- | --- |
| rc.1 | `3f9e9ba` | 2026-11-19 | Writing `v=2` left mixed-role clients with no safe upgrade order. |
| rc.2 | `5e033e0` | 2026-11-19 | Legacy zstd `MAXIMUM` messages were refused at the default limit; reflective lookups of `decompress(byte[])` broke. |
| rc.3 | `fba66d6` | 2026-11-20 | Strict UTF-8 validation held a payload-sized `char[]`. |
| rc.4 | `06b9a30` | 2026-11-21 | Fresh sends could enqueue messages that the same configuration refuses on receive. |
| rc.5 | `0fefe04` | 2026-11-21 | The upgrade guide missed FIFO content-based deduplication; the Guava entry overclaimed. |
| rc.6 | `bf864c7` | 2026-11-22 | No blocker: released. |

Between the candidates, the fixes landed through #208–#213. 0.6.0 is rc.6 plus two changes. The release PR changed
the version and the CHANGELOG heading (#214). #215 removed the expired release-age exception for zstd-jni `1.5.7-28`,
whose seven-day wait had ended.

### What each reviewer added

- **rc.1**
  - Sol found the blocker: writing `v=2` left clients that both send and receive, across queues, with no upgrade order
    that works. A first fix that let senders keep writing `v=1` (#207) was closed. The owner decided instead to keep
    `v=1`, always write `l` (#208), and read minor versions (#209).
  - Haiku found CHANGELOG version steps that didn't add up to the change from 0.5.1, and a dead-letter-queue sentence
    that was wrong without a DLQ.
  - Haiku's gap-hunter also flagged that fresh sends weren't checked against the decode limit. That was dismissed then
    as documented, and Sol made it a blocker in rc.4.
- **rc.2**
  - Sol A found two blockers:
    - 0.5.x zstd `MAXIMUM` messages, even small ones, were refused at the default limit, because their frames declare a
      128 MiB window. Fixed with a bounded one-step retry (#210).
    - Reflective `getDeclaredMethod("decompress", byte[].class)` on the built-in compressors failed. Fixed by declaring
      the method again.
  - Sol A also found the 1 GiB window limit on zstd-jni's 32-bit native builds. The owner chose to record it rather
    than fix it.
  - Haiku noticed that `defaultInterceptor()` now returned a new instance on each call. rc.5 restored 0.5.x's shared
    instance.
  - Sol B (scope) and Sol C (claims and docs) rated nothing as blocking. Their wording corrections went into rc.3.
- **rc.3**
  - Sol A found the blocker: strict UTF-8 validation decoded the payload into a `char[]` (about three times its size),
    so a valid message under the limit could exhaust a small heap. Fixed with a fixed-size buffer (#211).
  - Sol A's other blocker, the extra output buffer of one-pass zstd compression, was re-rated non-blocking in
    settlement and documented.
  - Haiku found test and wording gaps. Sol B and Sol C rated nothing as blocking.
- **rc.4**
  - Sol A found three blockers:
    - A fresh send could enqueue a message that receivers with the same limit refuse. Fixed by refusing such sends
      before compressing or encoding (#212).
    - The cross-version runs didn't show which native zstd-jni each process loaded. They were re-run with per-version
      runtime classpaths.
    - The new publishing path had no real run. A SNAPSHOT was published and resolved from a fresh consumer.
  - The rc.4 reviews' smaller findings were fixed in rc.5. They included the shared default interceptor and
    `CompressionException`'s serialization ID.
- **rc.5**
  - Astra found the blocker that every earlier review missed: 0.6.0's zstd bodies differ from 0.5.x's for the same
    payload, so on FIFO queues with content-based deduplication, a resubmission through the other version's writer can
    be delivered twice.
  - Astra also promoted Sol C's Guava finding to a blocker: the CHANGELOG read as if the library depended on Guava.
  - Both were fixed in #213. Astra agreed with re-rating Sol A's 2 GiB compression ceiling as non-blocking.
- **rc.6**
  - No blocker.
  - Haiku found that the zstd-jni release-age exception had expired, which led to #215, plus a test name and a Javadoc
    sentence.
  - Sol A rated two findings as blocking: PIT ignoring the test-JDK property, and the publish queue. Both were
    withdrawn in settlement and go to the next patch. Sol A also found the custom-compressor source break.
  - Sol B's scope notes needed no change.
  - Sol C found seven wording corrections.
  - Astra first asked for an rc.7 for the retry-exhaustion message, then re-rated it non-blocking.

  All of these are in `docs/coverage-gaps.md`.

### Rough cost per model

| Reviewer | Calls | Input tokens (uncached) | Output tokens |
| --- | --- | --- | --- |
| Haiku | 66 reviews | 55.9M (mostly cache reads) | 2.3M |
| Sol | 65 calls | 137.4M (4.9M) | 520K |
| Astra | 3 calls (rc.5, and two rounds on rc.6) | 4.7M (0.23M) | 21K |

Per candidate, a review and its settlement:
- **Haiku:** 3 to 7 reviews, about 3M to 7M input and 150K to 250K output per candidate.
- **Sol:**
  - rc.1: 7.4M input and 28K output, in one thread.
  - rc.2 to rc.6: three threads each (A, B and C), with about 17M to 26M input and 75K to 91K output per candidate.
    Sol A's thread also reviewed the fix PR for the next candidate.
- **Fix PRs between candidates:** the Haiku reviews of the fix PRs took a further 26M input in all. Most of that,
  15.4M, was for rc.1's redesign. Sol's design review of that redesign took 27.8M.
- **Wall time:** each Haiku reviewer worked for 2 to 14 minutes.

### Real runs

These were on rc.6 unless noted. They used LocalStack 4.14.0 and synthetic bodies.
- **Full check:** `./gradlew clean check pitest publishToMavenLocal` passed: 390 tests across 14 suites, none skipped,
  including the 8 LocalStack integration tests. PIT detected 238 of 240 mutations; the two survivors were the same as
  in rc.5.
- **Cross-version sends and receives:** 0.5.0 and 0.5.1 read rc.6's messages in all seven configurations, and rc.6
  read theirs. That covers zstd at the default and `MAXIMUM` levels, gzip, snappy, checksum-only and both encodings.
  rc.6 also read 0.5.1's messages sent without `l`, and tiny forced-zstd frames.
- **zstd frames:** the frame headers show that rc.6 writes single-segment frames with a content size, and 0.5.x
  writes 2 MiB or 128 MiB windows without one. That's the difference behind the FIFO note.
- **Refusals:** rc.6 refused zstd and gzip bombs, `v=2`, and invalid UTF-8. A real SDK client refused fresh sends over
  the limit, single and batch, before they reached SQS.
- **Binary compatibility:** a harness compiled against 0.5.1 linked and ran against rc.6. `javap` shows the same public
  members as rc.5.
- **Legacy JDKs:** the legacy-JDK workflow passed on the rc.6 tag, on JDK 17 and 21.
- **Covered by rc.5's runs only:** a relay forwarding a pre-encoded message without `l`, and a SNAPSHOT publish
  resolved from a fresh consumer.
- **The release itself:**
  - The release PR's full check passed with the same counts.
  - The publish from `v0.6.0` checked a 25-file inventory and uploaded the jar, sources, Javadoc, POM and module
    metadata, verifying each upload by reading it back.
  - A fresh consumer resolved `io.github.leanish:sqs-codec:0.6.0` from GitHub Packages. With it, gzip, zstd and
    checksum-only messages round-tripped, and an over-limit send was refused.
