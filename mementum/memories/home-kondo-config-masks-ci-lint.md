---
type: Insight
symbol: 💡
title: Home clj-kondo config masks findings that CI will surface
---

💡 The dev machine's `~/.config/clj-kondo/config.edn` globally disables
`:unused-referred-var`, `:unused-binding`, `:unused-namespace`, and
`:unused-private-var`. clj-kondo merges home config into every lint run, so a
local "0 warnings" is **not** the same claim as CI's "0 warnings" — CI (clean
environment, first honest lint of this project) surfaced 14 findings the home
config had been hiding: 12 genuine (dead requires, unused bindings) and 2
guardrails false positives (`=>`/`?` are used inside gspec vectors but the
shipped hook doesn't register them as var usages → narrow
`:unused-referred-var` exclude in tracked project config).

Reproduce CI lint locally:

```
XDG_CONFIG_HOME=/tmp/empty-xdg clj-kondo --lint src/main src/test
```

Related gotcha, same session: on a cold `~/.m2`, `$(clojure -Spath ...)`
interleaves `Downloading:` progress into the substituted classpath, silently
breaking `--copy-configs`. Prime with `clojure -P -A:test` first.

Rule: lint anchors ("0 warnings") only count when produced in a
CI-equivalent environment.
