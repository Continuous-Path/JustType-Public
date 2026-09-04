## Summary

<!-- What and why, briefly. -->

## iOS port categorization (required — see docs/ios-port/PORTING-WORKFLOW.md)

- [ ] **shared-core** — touches `jt-core/**`; flows to iOS via the next core release
- [ ] **port-needed** — changes platform behavior iOS must mirror → iOS port issue: #___
- [ ] **android-only** — Nav mode, overlays, IME plumbing, or other Android-specific code
- [ ] **Golden fixtures updated** (required if candidate ranking / case-learning / frequency behavior changed; implies a MINOR core bump)

## Testing

<!-- ./jt check output, manual steps taken. -->
