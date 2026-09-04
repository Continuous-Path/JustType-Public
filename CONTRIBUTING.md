# Contributing to JustType

Thanks for your interest. JustType is built by the Continuous Path Foundation, a
501(c)(3) nonprofit, for people who find ordinary touchscreens difficult or
impossible to use. That purpose shapes what we accept: **accessibility,
performance on inexpensive hardware, and backwards compatibility outrank
novelty.** A change that is elegant but drops older devices is usually the wrong
trade here.

## Signing off your work (DCO)

We do not ask you to sign a contributor licence agreement. Instead we use the
[Developer Certificate of Origin](https://developercertificate.org/) — a short
statement that you wrote the contribution, or otherwise have the right to submit
it under the project's licence.

Certify it by adding a `Signed-off-by` line to each commit:

```
Signed-off-by: Jane Doe <jane@example.com>
```

`git commit -s` adds it for you. Use your real name and an address where you can
be reached. By signing off you agree to the DCO, reproduced in full at the link
above.

## Licence

JustType's **code** is Apache-2.0. Its **language data** — the word lists, region
tags, generated databases and published language packs — is CC BY-SA 4.0, because
it derives from CC BY-SA sources. See [NOTICE](./NOTICE) for the attributions, and
`docs/.plans/language-resources/plan.md` for the sourcing rules any new language
data must follow. Do not add corpora under non-commercial or unstated licences.

## Before you open a pull request

Run the full gate — unit tests, formatting and static analysis in one pass:

```bash
./jt check
```

Use `./jt`, not `./gradlew` directly; the wrapper handles locking and orphaned
workers. `./jt report test` prints a readable failure summary. Settings changes
must appear on both the touchscreen and keyboard-overlay surfaces — see
`docs/settings-parity.md`.

Please also:

* Keep the change focused. One concern per pull request reviews far better.
* Match the surrounding code — its naming, its comment density, its idioms.
* Explain *why* in the commit message. The diff already shows what changed.
* Say what you tested, and on which device or Android version. "Tested on a
  Pixel Tablet, Android 15" is worth more than "works".

## Reporting bugs

Tell us what you expected, what happened, and how to reproduce it. For input or
prediction bugs, the exact key sequence and the on-screen result are the useful
details. Include the build identity shown at the foot of the settings screen —
it names the commit your build came from.

## Security and licensing

Please do not open a public issue for a security problem. Write to
**security@continuouspath.org** instead.

## Code of conduct

Participation is governed by our [Code of Conduct](./CODE_OF_CONDUCT.md).
Concerns go to **conduct@continuouspath.org**.
