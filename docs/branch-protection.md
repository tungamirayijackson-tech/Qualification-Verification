# Branch protection and repository setup

§10 of the manuscript treats branch protection as a deliverable, not a nicety: the repository
is 20% of the mark, and "meaningful commit history", "pull requests and code reviews" and
"merge conflict management" are assessed from what the repository actually shows. These are the
settings that make the workflow real rather than aspirational.

Screenshot the ruleset page once configured — the report appendix needs it as evidence.

## Ruleset on `main`

Settings → Rules → Rulesets → New branch ruleset, targeting `main`:

| Setting | Value | Why |
|---------|-------|-----|
| Require a pull request before merging | on | No direct pushes to the trunk |
| Required approvals | 1 | Every change is read by someone other than its author |
| Dismiss stale approvals on new commits | on | An approval applies to the code that was reviewed |
| Require review from Code Owners | on | Routes reviews by module, per `.github/CODEOWNERS` |
| Require status checks to pass | on | See the list below |
| Require branches to be up to date | on | Forces the rebase, which is where conflicts surface |
| Require linear history | on | Squash-merge only; the graph stays readable |
| Block force pushes | on | History is evidence; it must not be rewritable |
| Restrict deletions | on | |

## Required status checks

Names must match the job `name:` values in `.github/workflows/pr.yml`:

- `Backend build, test, analyse`
- `Integration tests (Testcontainers, real PostgreSQL 16)`
- `Frontend build, lint, test`
- `Secrets, dependencies and config scanning`
- `Requirement traceability matrix`

A red check disables the merge button. That is the mechanism behind the claim in §11 that the
pipeline *is* the requirements verification.

## Merge settings

Settings → General → Pull Requests:

- Allow **squash merging** only — disable merge commits and rebase merging.
- Default squash commit message: **pull request title**, so the trunk reads as one commit per
  issue with its PR number in the subject.
- Automatically delete head branches after merge.

## Environments

Settings → Environments:

| Environment | Protection | Variables |
|-------------|-----------|-----------|
| `staging` | none — deploys automatically | `STAGING_URL` |
| `production` | **required reviewers**: at least one team member | `PRODUCTION_URL` |

The required reviewer on `production` is the manual approval in `main.yml`. It is what makes
the pipeline continuous *delivery* rather than continuous deployment, and it is worth naming
explicitly in the viva.

## Secrets

None are committed, ever — `gitleaks` scans full history on every PR and fails the build.

| Secret | Used by | Needed for |
|--------|---------|-----------|
| `SONAR_TOKEN` | `pr.yml` | SonarCloud analysis; the step is skipped when unset |
| `GITHUB_TOKEN` | both | Provided automatically by Actions |

## Labels and the board

- Labels `FR-01` … `FR-12`, `NFR-01` … `NFR-07`, plus `feature`, `bug`, `chore`, `docs`.
- A Projects board with four columns: **Backlog → In progress → In review → Done**.
- Every issue carries its requirement label, so the chain requirement → issue → PR → commit is
  navigable in both directions.

## Local hooks

Two mechanical guards, so bad work does not reach a PR (§10):

- `commit-msg` — commitlint rejects a subject that is not a Conventional Commit.
- `pre-push` — runs the fast unit suite.

Both land with the frontend tooling in week 2, since Husky is installed through npm.
