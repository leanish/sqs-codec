# Release validation

How each release and its candidates were checked: what each reviewer added, the rough cost per model, and the evidence
from real runs, including the checks after publishing. Known limitations are in `docs/coverage-gaps.md`.

Reviewers, by role:
- **Haiku:** Claude Haiku sub-agents, read-only, each checking one area: claims, a checklist, or gaps.
- **Sol:** Codex reviews. Sol A reviews correctness, Sol B scope, and Sol C claims and docs, each in its own thread.
- **Astra:** a Codex model that challenged the earlier reviews on 0.6.0's rc.5 and rc.6.

Claude Opus implemented the changes and settled the findings with the reviewers. It sent the Haiku findings it
dismissed to Sol to confirm.

The usage table covers the logged Haiku, Sol and Astra reviews. Codex input is mostly cached thread context, so its
uncached share is given too. No prices are applied.

## 0.6.1 - 2026-11-22

Two candidates, from `v0.6.0`; the release is `v0.6.1` at `8ad5ea4`. The owner asked for the usual review of a patch
rather than an exhaustive pass, with no Astra: rc.1 had three Haiku reviewers and an unprimed Sol review, and rc.2 a
Sol check of its changes in the same thread.

| Candidate | Date | Why the next candidate was cut |
| --- | --- | --- |
| rc.1 | 2026-11-22 | Reviewers asked for narrower wording in comments, test text and a README example; rc.2 changed no production code. |
| rc.2 | 2026-11-22 | No blocker remained: released. |

rc.2's tree is the same as `main`'s just before the release PR (#220), which changed only the version and the
CHANGELOG headings.

### What each reviewer added

- **#218 and #219, before the candidates** (Sol on the plan and the diffs, Haiku on the diffs):
  - Sol found that the `serialVersionUID` first pinned came from a jar built at an earlier commit, not from the
    published 0.6.0 jar. The constant and its test pin the published jar's value.
  - Sol asked for evidence that PIT's worker JVMs run on the requested JDK. A verbose PIT run with the JDK 17 property
    showed every worker on Corretto 17.0.19.
  - Sol sharpened the wording for custom `Compressor` classes and the zstd retry-exhaustion message.
  - Haiku found that the publish queue keeps up to 100 waiting runs, not every one, and an awkward sentence in the
    CHANGELOG's Security wording. Sol agreed with Claude's dispositions of the Haiku findings it dismissed.
- **rc.1**
  - All three Haiku reviewers and Sol asked for the PIT evidence on JDK 17 to be repeated on the candidate. The rerun
    showed all 22 worker `java.home` lines on Corretto 17.0.19.
  - All three Haiku reviewers and Sol asked for narrower wording in comments and test text, which rc.2 made.
  - Sol found that a README example's comment missed the new retry-exhaustion refusal, fixed in rc.2.
  - Claude dismissed Haiku findings already settled in #218 and #219 (snappy advisory records, zstd wording); Sol
    agreed with those dispositions.
- **rc.2**
  - Sol confirmed that rc.2 made exactly the agreed edits, with no production code change.

### Rough cost per model

| Reviewer | Calls | Input tokens | Of which uncached | Output tokens |
| --- | --- | --- | --- | --- |
| Haiku, #218 and #219 | 3 reviews | 1.52M | - | 78K |
| Haiku, rc.1 | 3 reviews | 1.30M | - | 72K |
| Sol | 7 calls, 6 with recorded usage | 9.64M | 0.64M | 96K |

Haiku's 2.82M input is 2.40M cache reads, 0.42M cache writes and about 200 ordinary input tokens.

### Real runs

- **rc.1**
  - `./gradlew clean build pitest` on JDK 25 passed: 427 tests across 15 suites, none skipped, including the 8
    LocalStack integration tests. PIT detected 303 of 306 mutations, counting timeouts.
  - `test` with the JDK 17 and JDK 21 properties: 427 tests each, none skipped or failed. `pitest` with the JDK 17
    property detected 303 of 306 mutations as well.
  - A fresh consumer resolved the candidate from Maven local only. Calling the interceptor directly, checksum-only,
    gzip and zstd messages round-tripped, and over-limit fresh sends were refused by the code-unit pre-check and by the
    byte check.
- **rc.2**
  - The same build on JDK 25 and the tests on JDK 17 and 21 passed, with the same counts.
  - Its jar's 46 class files were byte-identical to rc.1's; the consumer run and the JDK 17 PIT run weren't repeated.
- **On `main` before the release PR:** hosted CI passed (Build, PIT, CodeQL, supply-chain, dependency graph).
- **The release itself:**
  - #220's `clean check` passed: 427 tests across 15 suites, including the 8 LocalStack integration tests, with no
    skips. CI passed on #220 and on the merge commit.
  - The publish from `v0.6.1` passed its JDK 25 build and its JDK 17 and 21 tests, then staged and uploaded the
    publication. GitHub accepted `queue: max`.
  - A fresh consumer resolved `io.github.leanish:sqs-codec:0.6.1` from GitHub Packages only. The same round trips and
    both refusals behaved as on rc.1.
- **Not exercised:** live AWS and FIFO delivery. The cross-version LocalStack runs weren't repeated, since 0.6.1
  changes no wire format.

## 0.6.0 - 2026-11-22

Six candidates, from `v0.5.1`. Tags are lightweight; the release is `v0.6.0` at `691c621`.

| Candidate | Commit | Date | Why the next candidate was cut |
| --- | --- | --- | --- |
| rc.1 | `3f9e9ba` | 2026-11-19 | Writing `v=2` left mixed-role clients with no safe upgrade order. |
| rc.2 | `5e033e0` | 2026-11-19 | Legacy zstd `MAXIMUM` messages were refused at the default limit; reflective lookups of `decompress(byte[])` broke. |
| rc.3 | `fba66d6` | 2026-11-20 | Strict UTF-8 validation held a payload-sized `char[]`. |
| rc.4 | `06b9a30` | 2026-11-21 | Fresh sends could enqueue messages that the same configuration refuses on receive. |
| rc.5 | `0fefe04` | 2026-11-21 | The upgrade guide missed FIFO content-based deduplication; the Guava entry overclaimed. |
| rc.6 | `bf864c7` | 2026-11-22 | No blocker remained after settlement: released. |

Between the candidates, the fixes landed through #208–#213. 0.6.0 is rc.6 plus two changes. The release PR changed
the version and the CHANGELOG heading (#214). #215 removed the expired release-age exception for zstd-jni `1.5.7-28`,
whose seven-day wait had ended.

### What each reviewer added

- **rc.1**
  - Sol's blocker: writing `v=2` left clients that both send and receive, across queues, with no upgrade order that
    works.
    - A first fix that let senders keep writing `v=1` (#207) was closed.
    - The owner decided instead that fresh sends that emit codec metadata write `v=1`, and fresh compressed sends also
      write `l` (#208). Readers accept canonical `1.<minor>` (#209).
  - Sol also found the limit above 1 GiB on zstd-jni's 32-bit native builds, which rc.2 took up.
  - Sol found that the new publishing path had no staging evidence. A staging run in settlement showed the
    expected 25-file inventory. Sol corrected a stale publisher pin in the records, and recorded two limitations: strict
    version spelling and the publish queue.
  - All three Haiku reviewers spotted the snappy-java version gap in the CHANGELOG.
    - The claims and checklist reviewers spotted the zstd-jni version steps.
    - The claims reviewer spotted the Checkstyle baseline and the dead-letter-queue wording.
    - The gap-hunter first flagged that fresh sends weren't checked against the decode limit. That was dismissed then
      as documented, and became a blocker in rc.4.
- **rc.2**
  - Sol A found the reflection regression: `getDeclaredMethod("decompress", byte[].class)` failed on the built-in
    compressors. Sol A also found gaps in the evidence: no candidate publish or legacy-JDK run, and the cross-version
    runs only as a summary.
    - Saved transcripts closed the cross-version gap, and legacy-JDK runs closed the JDK gap. Registry publication
      stayed a separate check at release time.
    - Sol A first rated the 32-bit limit from rc.1 as blocking. The owner deferred it, and it was recorded rather than
      fixed.
  - Sol B independently made legacy `MAXIMUM` frames its main scope issue: they were refused at the default limit,
    because 0.5.x declares a 128 MiB window for them. Sol B also raised the separate window-versus-output-budget
    question. Sol A rated the `MAXIMUM` refusal a rollout blocker too.
  - #210 restored the four `decompress(byte[])` declarations and added a bounded one-step retry. Sol A challenged the
    first plan, which allocated the full limit up front, and that led to a small first buffer with bounded growth.
  - Haiku and Sol both noticed that `defaultInterceptor()` now returned a new instance on each call. rc.5 restored
    0.5.x's shared instance.
  - Haiku found the snappy comment for an unreachable branch, and a bomb test whose assertion matched two messages.
  - Sol C corrected the wording on the decode bounds, the SDK's MD5 check, and CI.
- **rc.3**
  - Sol A elevated the full-buffer UTF-8 allocation, first raised on rc.2, to a blocker. The decoded byte array and a
    temporary `char[]` together took about three times the decoded size, so a valid message under the limit could
    exhaust a small heap. #211 switched validation to a fixed-size buffer.
  - Settlement re-rated Sol A's other blocker, the extra output buffer of one-pass zstd compression, as non-blocking.
    It's documented as a trade-off.
  - Sol B called out the 32-bit record and what custom `Compressor` implementers need.
  - Sol C found the buffered-window shortcut, what disabling the SDK's MD5 check also turns off, and migration wording.
  - Haiku found timeout, `l`-assertion and fixture gaps in the tests, which #211 closed.
- **rc.4**
  - Sol A found three blockers:
    - A fresh send could enqueue a message that receivers with the same limit refuse. #212 refuses such sends before
      compressing or encoding.
    - The cross-version runs didn't show which native zstd-jni each process loaded. The run notes added resolved
      per-version runtime classpaths.
    - The new publishing path had no real run. A SNAPSHOT was published and resolved from a fresh consumer.
  - Sol A also found that the added constructor changed `CompressionException`'s computed serialization ID. #212 pins
    0.5.1's ID.
  - Sol B prompted a new 0.5.0 run matrix and backed restoring the shared default interceptor, which #212 did.
  - Haiku prompted a real send probe with sync and async SDK clients.
  - The fix review overturned a claim that the Lombok-generated `withMaxDecodedPayloadBytes` was missing.
  - Sol C contributed documentation corrections. Some test and behavior gaps were left as known limitations.
- **rc.5**
  - Astra identified what the different zstd bodies mean for FIFO queues. That 0.6.0's bodies differ from 0.5.x's for
    the same payload had been observed since rc.1. Astra saw that on queues with content-based deduplication, a
    resubmission through the other version's writer can be delivered twice.
  - Astra also elevated Sol C's Guava finding to a blocker. The CHANGELOG's Guava entry claimed a build-tool fix
    broader than the evidence: it covered Error Prone and Checkstyle, while an older Guava remained a Spotless input.
    The publication descriptors declare no Guava dependency.
  - #213 added FIFO cutover guidance and narrowed the Guava entry; runtime behavior didn't change.
  - Sol A re-rated its own 2 GiB compression-ceiling finding as non-blocking, and Astra concurred.
  - Sol B traced how the three rc.4 blockers were closed.
  - Haiku found that rc.5 had lost the corrupt-payload tests without `l`, which #213 restored. The fix review caught
    caveats that still had to attach to the upgrade and rollback claims.
- **rc.6**
  - No blocker remained after settlement.
    - Sol A withdrew its blocker ratings for PIT ignoring the test-JDK property and for the publish queue. The queue was
      already known from rc.1 and settled in rc.5.
    - Astra re-rated the retry-exhaustion message as non-blocking, keeping it as a defect.
  - Haiku confirmed that the zstd-jni release-age exception had expired, and supplied a scan run after the expiry. That
    led to #215, a follow-up that was already planned.
  - Haiku raised the custom-compressor issue, which Sol A refined into a source-compatibility omission. Haiku also
    raised the test-name and Javadoc issues.
  - Sol A and Astra rebutted Haiku's claim that the legacy readers hadn't read rc.6's one-pass zstd: the runs show both
    did.
  - Sol B identified a possible length check before the UTF-8 conversion. It also noted that the summaries of earlier
    candidates established the main fixes, but not the closure of each unnamed rc.4 and rc.5 finding.
  - Sol C separated seven wording corrections in the published text from corrections to the run notes.
  - The remaining public limitations are in `docs/coverage-gaps.md`.

### Rough cost per model

| Reviewer | Calls | Input tokens | Of which uncached | Output tokens |
| --- | --- | --- | --- | --- |
| Haiku, candidate reviews | 36 reviews | 29.9M | - | 1.22M |
| Haiku, fix-PR and PR-text reviews | 30 reviews | 26.1M | - | 1.10M |
| Sol | 65 logged calls, 64 with recorded usage | 137.4M | 4.9M | 520K |
| Astra | 3 calls (rc.5, and two rounds on rc.6) | 4.7M | 0.23M | 21K |

Haiku's 56.0M input is 49.0M cache reads, 7.0M cache writes and about 3K ordinary input tokens.

Per candidate:
- **Haiku:** 3 to 7 reviews of each candidate, with about 3M to 7M input and 150K to 250K output.
- **Sol:**
  - rc.1: 7.4M input and 28K output, in one thread.
  - rc.2 to rc.6: three threads each, with about 17M to 26M input and 75K to 91K output per candidate. Sol A's rc.2 to
    rc.5 threads also reviewed the fix PR for the next candidate.
- **rc.1's redesign:** 15.4M of the Haiku fix-PR input, and 27.8M of Sol design review.

### Real runs

These were on rc.6 unless noted.
- **Full check:** `./gradlew clean check pitest publishToMavenLocal` passed: 390 tests across 14 suites, none skipped,
  including the 8 LocalStack integration tests. PIT detected 238 of 240 mutations; the two survivors were the same as
  in rc.5.
- **Cross-version sends and receives**, on LocalStack 4.14.0 standard queues with synthetic bodies:
  - 0.5.0 and 0.5.1 read rc.6's messages in all seven ordinary configurations, and rc.6 read theirs. That covers zstd
    at the default and `MAXIMUM` levels, gzip, snappy, checksum-only and both encodings.
  - rc.6 also read 0.5.1's messages sent without `l`.
  - The tiny forced-zstd probes returned the expected short text.
  - rc.6 accepted a hand-built `v=1.3` message that 0.5.1 refused.
- **zstd frames:** the captured fixtures differed in framing.
  - rc.6's small and ordinary frames were single-segment, with a content size that implies their window. The unit tests
    also cover an 8 MiB one-pass frame, which has a separate window descriptor.
  - 0.5.x's frames declared 2 MiB or 128 MiB windows and no content size.
  - That difference is behind the FIFO note.
- **Refusals:** rc.6 refused zstd and gzip bombs, `v=2`, and invalid UTF-8. A real SDK client pointed at LocalStack
  refused fresh sends over the limit, single and batch, before they reached the queue.
- **Binary compatibility:** selected calls compiled against 0.5.1 ran on rc.6, some checked only by length. The
  sorted `javap` member listings matched rc.5's.
- **Legacy JDKs:** the legacy-JDK workflow passed on the rc.6 tag, on JDK 17 and 21.
- **Not repeated on rc.6:**
  - the relay forwarding a pre-encoded message without `l`, recorded on rc.2 to rc.5;
  - the two tiny-frame cases for the legacy readers that were covered only on rc.5 (rc.6 repeated one per reader);
  - the SNAPSHOT publish and consumer resolution, on rc.5.
- **The release itself:**
  - #214's `clean check` passed: 390 tests across 14 suites, including the 8 LocalStack integration tests, with no
    skips.
  - The publish from `v0.6.0` checked a 25-file inventory and uploaded the jar, sources, Javadoc, POM and module
    metadata, verifying each upload by reading it back.
  - A fresh consumer resolved `io.github.leanish:sqs-codec:0.6.0` from GitHub Packages. Calling the interceptor
    directly, gzip, zstd and checksum-only messages round-tripped, and an over-limit send was refused.
- **Not exercised:** live AWS, FIFO delivery, and an async receive. Async receive was checked against the SDK 2.57.13
  source only; the SDK probe exercised sync and async send refusal.
