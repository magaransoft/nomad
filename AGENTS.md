# Nomad — Project Agent Context

## Maintainer

`novamage@magaran.com` (GPG `6DC61FDE08C36DE2`) = main pusher maintainer.
Owner Shortcut from [org AGENTS.md](../../AGENTS.md) applies here →
`gh pr merge --admin --merge` and direct `git push` work without joining
`temporary-pr-overlord` or `temporary-hotfix-overlord`.

Agent path: verify owner via signed-commit check (`git log -1 --pretty=format:'%G? %GS %ae %GK'` — see "Owner Verification" in org AGENTS.md),
then merge/push directly. On 403: stop, report.
