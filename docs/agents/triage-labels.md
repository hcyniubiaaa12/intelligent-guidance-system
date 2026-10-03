# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

Both columns are identical: this repo keeps the default vocabulary. When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

## How state is recorded here

This repo uses a local-markdown issue tracker, so there are no tracker-side labels. The label string is written as a `Status:` line near the top of the issue file:

```markdown
Status: ready-for-agent
```

A ticket may legitimately carry more than one role — comma-separate them. If you change a ticket's role, edit that line in place rather than appending a new one.
