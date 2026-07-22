---
type: Mistake
symbol: ❌
title: Save/delete middleware silently swallowed transaction failures
---

❌ The original `save-form!` and `delete-entity!` caught transaction exceptions,
logged, and returned `{}` — reporting SUCCESS on failure. This hid
`:transact/attr-pred` and `:db/ensure` post-condition violations from the
client. Fix: rethrow via `ex-info` with context (`{:schema :txn-data}` for save,
`{:ident :schema}` for delete) so failures propagate to the caller/client. Watch
for this swallow-and-return-`{}` anti-pattern in any new middleware — a failed
write must never look like a successful one.
