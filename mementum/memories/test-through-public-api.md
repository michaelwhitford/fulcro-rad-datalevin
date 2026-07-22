---
type: Win
symbol: ✅
title: Test through the public API, not private vars
---

✅ Datalevin's own suite reaches into private vars in only 2 of 40 files; the
norm is testing behavior through the public API. Applied here: tests reached
`#'start-databases/enumerated-values` (a private var). Fixed by having
`create-test-conn` call the public `start-database!` (which builds schema and
seeds enum idents) and asserting the persisted result via `d/q`/`d/pull`. Tests
the contract, not the implementation; every test now exercises the real public
entry point, and the refactor removed duplication (net-negative lines). When a
test needs an internal, prefer exercising the public path that uses it.
