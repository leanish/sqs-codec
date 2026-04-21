# ASCII85 decisions

This note records the design decisions behind the experimental `ASCII85`
payload encoding support.

## Context

ASCII85 has multiple dialects and compatibility modes in the ecosystem.
Common differences include:

- Adobe framing markers (`<~` and `~>`)
- Whitespace tolerance
- Zero/run shorthands such as `z`
- Less common non-standard shorthands such as `y`
- Different expectations for partial trailing blocks

For `sqs-codec`, the feature is meant to transport payloads between one
`sqs-codec` producer and one `sqs-codec` consumer. It is not intended as a
general-purpose interoperability layer for arbitrary external ASCII85 tools.

## Decisions

### Canonical emit

The library always emits a single canonical ASCII85 representation:

- no framing markers
- no whitespace
- no `z` shorthand
- no `y` shorthand
- standard partial-block encoding for trailing bytes

This keeps the wire format deterministic and easy to reason about.

### Canonical decode

The library accepts only the same canonical representation that it emits.

It intentionally rejects:

- framed Adobe-style payloads
- payloads containing whitespace
- shorthand forms such as `z` and `y`
- malformed or overflowing 5-character groups

This avoids dialect drift between producers and consumers and prevents the
decoder from silently accepting multiple textual representations for the same
payload.

### In-repo implementation

The implementation is maintained in this repository instead of depending on an
external ASCII85 library.

Reasoning:

- the dialect is small and explicit
- external implementations vary in behavior
- the project only needs one strict transport-oriented form
- keeping the codec local makes the accepted wire format obvious in tests and code

### Transport-focused scope

`ASCII85` is documented as experimental, but the chosen behavior is strict by
design. The goal is not to be liberal in what we accept; the goal is to keep
the transport format stable between `sqs-codec` endpoints.

### Size trade-off

`ASCII85` is smaller than Base64 in theory, but the practical size win is
modest. The feature exists as an optional transport trade-off, not as a new
default encoding for every payload.

## Non-decisions

The library does not currently try to optimize for:

- interoperability with third-party ASCII85 tools
- copy/paste compatibility with Adobe-style examples
- accepting alternate spellings of the same encoded payload

If that changes in the future, it should be treated as an intentional wire
format compatibility decision, not as a parser convenience tweak.
