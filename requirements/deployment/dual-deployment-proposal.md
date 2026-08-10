# Dual-Provider Deployment Proposal — Hetzner + netcup

**Status:** Proposal / decision document
**Supersedes nothing.** Extends [`deployment-proposal.md`](deployment-proposal.md), which remains the authority on
stack shape, resource limits, TLS, monitoring and DR objectives. This document covers **only** what changes when the
underlying VPS provider becomes a choice rather than a constant.

---

## 1. Summary and recommendation

The current deployment (`deploy/`, `docker-compose.yml`, `.github/workflows/deploy.yml`, `docs/deployment/`) is
**~90 % provider-agnostic already**. Everything above the OS — Docker Compose, Traefik, the LGTM stack, the Spring Boot
image, the `pg_dump` → S3 backup path, UptimeRobot — runs identically on any Ubuntu box with Docker.

Provider coupling is concentrated in exactly four places:

| Concern | Coupled artefact | Why it is Hetzner-specific |
| :--- | :--- | :--- |
| Block storage | `deploy/provision.sh` §7 | Formats and mounts `/dev/sdb`, a Hetzner Cloud Volume |
| Network firewall | `deploy/firewall/apply-firewall.sh` | Drives the `hcloud` CLI |
| Snapshots | `deploy/backup/volume-snapshot.sh`, `restore-from-snapshot.sh` | Calls `api.hetzner.cloud/v1/volumes/{id}/actions/create_snapshot` |
| Naming/docs | `.env.example`, `secrets-reference.md`, `first-time-setup.md`, `backup-restore.md` | `HCLOUD_TOKEN`, `HETZNER_VOLUME_ID`, `HOS_*`, "Hetzner Console" runbook steps |

**Recommendation:**

1. **Adopt a provider-plugin structure** (§5). One compose stack, one `provision.sh`, one deploy workflow; a thin
   `deploy/providers/<provider>/` layer implements the four coupled concerns behind a fixed script contract.
2. **Make the deploy workflow target-selectable** via a `workflow_dispatch` choice input bound to a GitHub
   **Environment** (§6). This is the mechanism you asked for and it is genuinely cheap — roughly a 15-line diff to
   `deploy.yml` plus two GitHub Environments.
3. **Replace cloud-volume snapshots with a provider-agnostic `restic` backup of `/opt/skillars/data`** (§8). This is
   the single most important change. It is not motivated by netcup alone — it removes the Hetzner API from the DR path,
   makes the recovery procedure identical on both providers, and turns the two providers into mutual DR sites.
4. **Run netcup on the hourly tariff for a 2–4 week bake** against a staging domain before committing to a 12-month
   term (§10).

**On the provider choice itself:** the Hetzner baseline in `deployment-proposal.md` no longer exists as written. The
**CX32 is deprecated**, Hetzner changed prices **three times in 2026**, and — the finding that matters most — the
cost-optimised **CX33 is frequently sold out and the entire CAX line is out of stock everywhere** (§2.1–§2.2). The
plan that is dependably purchasable is CPX31, at roughly double the price. Against CPX31, netcup VPS 1000 G12 costs
**less than half** all-in while shipping 256 GB of local NVMe instead of 160 GB plus a billed network-attached volume
(§2.4).

The availability finding is not a purchasing inconvenience — **it breaks the stated DR objective.** The recovery plan
for total node loss assumes a replacement can be bought on demand, and an RTO of "under 4 hours" is not achievable if
the plan you need is sold out for a day. §2.2 sets out the three design consequences; the most important is that
recovery must target a *shape*, not a SKU, which is precisely what the provider-plugin work below delivers.

The two material trade-offs against netcup: (a) netcup VPS vCores are **shared**, not guaranteed (netcup's
guaranteed-CPU line is "Root Server", not "VPS") — for a box running a JVM, PostgreSQL and the LGTM stack together,
verify this under load during the bake; and (b) netcup has **no S3 object storage**, so offsite backup must live at a
third party regardless (§8).

---

## 2. Provider comparison — verified facts

Figures below were checked against both providers' product pages and help centres in August 2026. Prices change;
re-verify in the console at purchase. Note the VAT asymmetry: Hetzner quotes **net**, netcup quotes **gross** to
consumers — the totals in §2.4 are stated both ways to keep the comparison honest.

### 2.1 The Hetzner baseline has moved

`deployment-proposal.md` and `server-proposal_old.md` are written around CX32, CX22 and CX42. **All three plan names are
deprecated.** Hetzner's Gen2 CX line was superseded by Gen3 (CX23 / CX33 / CX43 / CX53), and prices rose on
**15 June 2026, 08:00 CEST** for new orders and rescales — existing servers keep their legacy rate until an account
change moves them.

| Old name (in our docs) | Current equivalent | Old € net | New € net |
| :--- | :--- | :--- | :--- |
| CX22 | CX23 (2 vCPU / 4 GB / 40 GB) | 3.99 | **5.49** |
| **CX32** | **CX33 (4 vCPU / 8 GB / 80 GB NVMe)** | ~6.4 | **~8.49** |
| CX42 | CX43 (8 vCPU / 16 GB / 160 GB) | 11.99 | **15.99** |
| — | CCX13 (dedicated vCPU, for reference) | 15.99 | **42.99** |

Two further constraints that were not in the original proposal:

- **The CX line is EU-only** — available in `eu-central` (Falkenstein, Nuremberg, Helsinki) and **not** in Ashburn,
  Hillsboro or Singapore. The same applies to the ARM CAX line. If a Hetzner console project is set to a US or APAC
  location, CX33 does not appear at all and only the (far more expensive) CPX and CCX lines are offered. This is the
  most likely reason the cost-optimised plans look missing.
- **The primary IPv4 is billed separately** (~€0.60/mo net — verify), and was not accounted for in the original €18
  estimate.

The CCX13 line item is included above because it is the clearest evidence for §14's real point: **provider pricing risk
is now demonstrated rather than hypothetical.** A 169 % increase on one line in a single adjustment is exactly the
scenario the portability work in this document insures against, and it argues for doing that work whichever provider
wins.

### 2.2 Availability is now the binding constraint, not price

The cost-optimised plans are **listed but frequently not orderable**. As of August 2026:

- **CX33 is intermittently out of stock**, in and out over hours to days — one tracked instance shows it returning to
  HEL1 after ~21 hours sold out.
- **The entire CAX (ARM) line — CAX11, CAX21, CAX31 — is out of stock in every location it is offered.**
- **CPX remains reliably available** in all six locations, at roughly double the price (§2.4).

This is structural, not a transient blip. Alongside the 15 June 2026 adjustment Hetzner introduced a **"-1-Ltd"
(Limited) hardware tier** built on hardware sourced at lower cost, and states plainly that such tiers are offered
**"as long as supply lasts"**, with availability driven largely by cancellations and sometimes taking weeks to
accumulate meaningful stock. Cheap capacity at Hetzner is now a deliberately rationed product, not a standing offer.

**This attacks the DR commitment directly, and that matters more than the money.**

The recovery path in §8.5 for total node loss is *provision a replacement → restic restore → dump restore → DNS*, held
to an RTO of under 4 hours. That plan silently assumes the replacement can be bought on demand. If CX33 is sold out
when the incident happens — and it demonstrably is, for many hours at a stretch — the RTO is not 4 hours, it is
*however long Hetzner takes to restock*, which nobody controls and which no runbook can shorten.

Three consequences, all of which change the design rather than just the purchase decision:

1. **A DR plan may not depend on a specific plan being purchasable.** Recovery must target a *shape* (4 vCPU / 8 GB /
   Ubuntu / Docker), not a SKU — which is exactly what the provider-plugin work in §5 delivers, and is now a
   requirement rather than a nicety.
2. **Identify a fallback shape at each provider in advance and record it in the runbook.** At Hetzner that means
   accepting CPX31 at ~2× the price during an incident; at netcup it means the next VPS or Root Server tier up. Paying
   double for a week to restore service is obviously correct; discovering mid-incident that there is nothing to buy
   is not.
3. **Rehearse the restore onto the fallback shape**, not just onto a like-for-like box, during the quarterly drill.

The corollary for provider choice: Hetzner is not merely more expensive now, it is **less dependably procurable at the
price that made it attractive.** netcup's VPS 1000 G12 was orderable throughout this evaluation, including on the
no-commitment hourly tariff. Confirm that at the moment of purchase — availability is a snapshot, and this section will
age faster than any other in the document.

### 2.3 Head to head

| | **Hetzner CX33** (successor to CX32) | **Hetzner CPX31** (the reliably orderable option) | **netcup VPS 1000 G12** |
| :--- | :--- | :--- | :--- |
| Orderable today | **Intermittently — often sold out** | Yes, all six locations | Yes |
| vCPU | 4 (shared, Intel) | 4 (shared, AMD EPYC) | 4 vCore KVM (**shared** — see caveats below) |
| RAM | 8 GB | 8 GB | 8 GB DDR5 ECC |
| Primary disk | 80 GB NVMe | 160 GB NVMe | **256 GB NVMe** |
| Extra storage | Cloud Volume — network-attached, detachable, independently snapshottable, **€0.0572/GB/mo** | ← same | Local Block Storage — up to 8 TB / 5 units, **not detachable, online snapshots not possible** |
| Network | 1 Gbit/s, 20 TB included | ← same | 2.5 Gbit/s; soft policy — throttled to 200 Mbit/s if 24 h average exceeds 2 TB |
| Locations | **EU only** — Falkenstein, Nuremberg, Helsinki | All six — adds Ashburn, Hillsboro, Singapore | Nuremberg, Vienna, Amsterdam, Manassas (US), Singapore |
| Network firewall | Hetzner Cloud Firewall — free, API/CLI-driven, IaC-friendly | ← same | SCP Firewall — free (Gen 9+), network-level, ingress/egress, TCP/UDP, ACCEPT/DROP, CIDR + port ranges |
| Firewall limits | Generous | ← same | **500 active rules** per server/interface; 100 source IPs per rule; **no REJECT**; stateful for **TCP only** |
| Snapshots | Volume snapshots, offsite, **€0.0143/GB/mo**, full REST API | ← same | Server snapshots, copy-on-write, no slot limit, free, but **stored on the same host and consuming your own 256 GB** |
| Snapshot export | N/A (already offsite) | ← same | Limited free export slots, then ~€1.50 each; download link valid ~48 h |
| Object storage | S3-compatible, `s3.<region>.hetzner.com` | ← same | **None** |
| API | `hcloud` CLI + REST, plain bearer token, CI-friendly | ← same | SCP REST API (`servercontrolpanel.de/scp-core/api/v1`), **OAuth2 device-code flow** — *not* CI-friendly |
| Billing | Hourly, capped monthly, no commitment | ← same | 12-month term at the headline price, **or** an hourly tariff (1/720 of monthly per hour, monthly cap) with a 6-month minimum prepayment, pro-rata refundable |

### 2.4 All-in monthly cost, re-priced

| Line item | **Hetzner CX33** (when in stock) | **Hetzner CPX31** (what you can actually buy) | **netcup VPS 1000 G12** |
| :--- | :--- | :--- | :--- |
| Compute | ~€8.49 net | **~€16.49 net** | €8.71 net (€10.36 gross, 12-month term) |
| Primary IPv4 | ~€0.60 net | ~€0.60 net | included |
| Data storage | 100 GB Volume @ €0.0572 = **€5.72 net** | €5.72 net | included in 256 GB NVMe — **€0** |
| Snapshots | ~100 GB @ €0.0143 = **€1.43 net** | €1.43 net | free (consumes own disk) |
| Offsite object storage | ~€3–6 net | ~€3–6 net | ~€3–6 net at a third party (§8.3) |
| **Total** | **~€19–22 net → ~€23–27 gross** | **~€27–30 net → ~€32–36 gross** | **~€12–15 net → ~€14–18 gross** |

Against the plan that is *reliably purchasable* — CPX31 — **netcup costs less than half**, and still ships 256 GB of
local NVMe against 160 GB, with no separate volume charge. Even against CX33 at its listed price, netcup wins on cost
and on disk. The disk being local NVMe rather than a network-attached volume is a latency advantage for PostgreSQL, not
merely a capacity one.

Caveats, none of which move the conclusion:

- **The exact CX33 price is genuinely uncertain.** Secondary sources disagree — one reports €5.31 after the 1 April
  adjustment, another €8.49 after 15 June. There were **three separate Hetzner price changes in 2026** (1 April,
  29 April, 15 June), which is very likely the source of the confusion, and Hetzner's own tables render client-side so
  they could not be read directly. The direction is unambiguous; the figure needs console confirmation.
- netcup's headline price assumes a **12-month commitment**; the hourly tariff carries a modest premium — confirm at
  checkout, along with any one-off setup fee.
- Hetzner Object Storage's current tier was not verifiable; the €3–6 range is carried from the original proposal and
  applies to both providers equally, so it cancels out of the comparison.

### Caveats worth a decision, not just a note

- **Shared vCores.** netcup's VPS line does not guarantee CPU; the Root Server (RS) line does. The Skillars box runs a
  JVM, PostgreSQL, Redis, Traefik and four LGTM services simultaneously. If p95 latency degrades under noisy-neighbour
  conditions during the bake, the answer is an RS-line box, not a bigger VPS. Budget for that possibility.
- **Snapshots are not backups on netcup.** They live on the same host, on your own disk, and cover the whole server
  rather than a data volume. Reverting rolls back the OS and any config drift with it. Treat them as a *fast rollback*
  mechanism only. This is why §8 exists.
- **Single filesystem.** With no separate data volume, `/opt/skillars/data` shares the root filesystem with Docker
  images, container logs and the OS. Filling it takes the *whole node* down, not just the database. Mitigations in §7.
- **The SCP REST API is not automatable from CI.** Device-code OAuth2 means a human completes the flow and a refresh
  token is cached locally. Firewall and snapshot automation on netcup therefore runs from an operator workstation, not
  from GitHub Actions, and netcup credentials must never become repository secrets.

---

## 3. Defects this work surfaces (fix regardless of provider choice)

These are pre-existing and independent of netcup, but the dual-provider design has to resolve them, so they are called
out here rather than buried.

**3.1 — The Hetzner firewall as configured blocks the deploy workflow.**
`deploy/firewall/apply-firewall.sh` restricts inbound TCP 22 to `${SSH_ALLOWLIST_IP}/32`. `.github/workflows/deploy.yml`
SSHes to the node from a GitHub-hosted `ubuntu-latest` runner with an arbitrary, rotating egress IP. Applying the
firewall as documented and then running the deploy workflow cannot both succeed. Options:

| Option | Verdict |
| :--- | :--- |
| Allow 22 from GitHub Actions' published ranges (`api.github.com/meta`) | **Not viable on netcup** — thousands of CIDRs against a 500-rule / 100-IP-per-rule ceiling. Fragile on Hetzner too. |
| Self-hosted runner inside the node's network | Works, adds a component to maintain |
| Pull-based deploy: node polls GHCR, or an HMAC-authenticated webhook behind Traefik triggers `docker compose up` | **Best end state** — port 22 stays pinned to the operator IP on both providers |
| Leave 22 open to the world; key-only auth, `PasswordAuthentication no`, fail2ban | **Pragmatic default for now** — already provisioned by `provision.sh` §3–4 |

**Recommendation:** ship the pragmatic default (22 open, key-only, fail2ban) and make `firewall-rules.json` say so
honestly instead of documenting a `/32` that is not in force. Record pull-based deploy as the hardening follow-up.

**3.2 — `DiskDataVolumeHigh` will silently never fire on netcup.**
The rule in `deploy/lgtm/alerts.yml` selects `mountpoint="/opt/skillars/data"`. On netcup that mountpoint does not
exist, the series is absent, and the `and … > 0` guard makes the alert evaluate to nothing — no data, no alert, no
error. Fix with a single rule that works on both (§7.3).

**3.3 — Every Hetzner plan name in the requirements docs is deprecated.**
`deployment-proposal.md` §1 (CX32), §4 (cost table) and §5 (scaling path: "dedicated CX42", "a second CX32") and
`server-proposal_old.md` all name Gen2 plans that can no longer be ordered, at prices that predate the June 2026
adjustment (§2.1). The scaling path in particular would send a future reader shopping for products that do not exist.
Rewrite as CX23 / CX33 / CX43 with current prices, or — better, given this document — rewrite the scaling path in
capability terms ("a 4 vCPU / 8 GB node", "a 8 vCPU / 16 GB node") so it survives the next rename at either provider.

**3.4 (minor)** — `deployment-proposal.md` §3 specifies "Hetzner's native platform monitoring" for external uptime
checks. The implementation actually uses UptimeRobot (`docs/deployment/uptime-monitor.md`), which is already
provider-agnostic and needs no change. Correct the proposal text.

---

## 4. Design principle

> **The provider is a property of the node, recorded in `/opt/skillars/.env`, not a property of the code.**

Three layers:

| Layer | Contents | Changes for netcup |
| :--- | :--- | :--- |
| **1 — Agnostic** | `Dockerfile`, `docker-compose.yml`, `deploy/traefik/`, `deploy/lgtm/`, `deploy/backup/pg-backup.sh`, `restore-from-dump.sh`, `install-crons.sh`, app config, UptimeRobot | Nothing, beyond parameterising two hard-coded paths |
| **2 — Parameterised** | `deploy/provision.sh`, `.env`, `.github/workflows/deploy.yml`, `alerts.yml` | Behaviour selected by `DEPLOY_PROVIDER` / workflow input |
| **3 — Provider plugins** | `deploy/providers/{hetzner,netcup}/` | New; four scripts each, fixed contract |

Because netcup VPS 1000 G12 is spec-matched to CX33 (4 vCPU / 8 GB), **no compose overlay is needed** — every
`deploy.resources.limits` value in `docker-compose.yml` carries over unchanged. Contrast `docker-compose.uat-hostwinds.yml`,
which needed a full overlay because that box was 1 vCPU / 1 GB. The overlay pattern exists in the repo if a future
provider needs it; netcup does not.

---

## 5. Provider plugin layer

### 5.1 Directory layout

```
deploy/
  provision.sh                      # unchanged responsibilities; dispatches to the plugin for disk setup
  providers/
    common/
      data-dirs.sh                  # mkdir + chown of postgres/prometheus/loki/tempo/grafana subdirs (extracted
                                    # from provision.sh §7 and restore-from-snapshot.sh §D — currently duplicated)
    hetzner/
      provider.env                  # DEPLOY_PROVIDER=hetzner, DATA_DEVICE=/dev/sdb, HAS_CLOUD_VOLUME=true
      disk-setup.sh                 # format/mount /dev/sdb, fstab entry  (moved out of provision.sh §7)
      firewall-apply.sh             # moved from deploy/firewall/apply-firewall.sh
      snapshot-create.sh            # moved from deploy/backup/volume-snapshot.sh
      snapshot-restore.sh           # moved from deploy/backup/restore-from-snapshot.sh
    netcup/
      provider.env                  # DEPLOY_PROVIDER=netcup, DATA_DEVICE=, HAS_CLOUD_VOLUME=false
      disk-setup.sh                 # no device to mount; asserts free space on / and warns below threshold
      firewall-apply.sh             # prints the SCP console procedure; optional scripted path via netcup-scp-cli
      snapshot-create.sh            # optional SCP snapshot; exits 0 with a notice if unconfigured
      snapshot-restore.sh           # prints the SCP revert procedure, then runs the shared post-restore verify
  backup/
    pg-backup.sh                    # unchanged logic; S3_BACKUP_* naming (§8.4)
    restic-backup.sh                # NEW — the real DR tier (§8.2)
    restore-from-dump.sh            # unchanged logic; S3_BACKUP_* naming
    restore-from-restic.sh          # NEW
    snapshot.sh                     # NEW — thin dispatcher → providers/$DEPLOY_PROVIDER/snapshot-create.sh
    install-crons.sh                # extended to install the restic cron
  firewall/
    firewall-rules.json             # becomes the provider-neutral declarative source of truth
    verify-firewall.sh              # NEW — external probe, detects drift on either provider (§9.2)
```

### 5.2 Plugin contract

Every plugin script:

- is idempotent and safe to re-run;
- exits **0** with an explanatory notice when the operation does not apply to that provider (netcup has no volume to
  mount — that is a successful no-op, not a failure);
- reads configuration only from the sourced `/opt/skillars/.env` plus its own `provider.env`;
- never prompts unless it genuinely requires a console action, in which case it prints the exact steps and blocks on
  `read -r -p` — the pattern `restore-from-snapshot.sh` already uses for the Hetzner Console.

`provision.sh` gains a dispatch block replacing §7:

```bash
DEPLOY_PROVIDER="${DEPLOY_PROVIDER:-}"
if [ -z "${DEPLOY_PROVIDER}" ] && [ -f "${DEPLOY_ROOT}/.env" ]; then
  DEPLOY_PROVIDER=$(grep -E '^DEPLOY_PROVIDER=' "${DEPLOY_ROOT}/.env" | cut -d= -f2- || true)
fi
case "${DEPLOY_PROVIDER}" in
  hetzner|netcup) ;;
  *) err "DEPLOY_PROVIDER must be 'hetzner' or 'netcup' (got '${DEPLOY_PROVIDER}')"; exit 1 ;;
esac

PROVIDER_DIR="${DEPLOY_ROOT}/deploy/providers/${DEPLOY_PROVIDER}"
# shellcheck source=/dev/null
. "${PROVIDER_DIR}/provider.env"
bash "${PROVIDER_DIR}/disk-setup.sh"
bash "${DEPLOY_ROOT}/deploy/providers/common/data-dirs.sh"
```

Everything in `provision.sh` §1–6 (packages, Docker, SSH hardening, fail2ban, ufw, directory structure, `acme.json` and
`.env` permissions) is provider-agnostic and stays exactly as it is. `awscli` in §1 stays; add `restic`.

### 5.3 `netcup/disk-setup.sh` in outline

```bash
#!/usr/bin/env bash
# netcup VPS: no attachable cloud volume. /opt/skillars/data lives on the root NVMe.
# Local Block Storage is deliberately NOT used — netcup documents it as not detachable
# and not online-snapshottable, so it provides none of the properties a Hetzner Volume did.
set -euo pipefail
DATA_DIR="${DATA_ROOT:-/opt/skillars/data}"
MIN_FREE_GB="${MIN_FREE_GB:-60}"

mkdir -p "${DATA_DIR}"
AVAIL_GB=$(df -BG --output=avail "${DATA_DIR}" | tail -1 | tr -dc '0-9')
echo "[disk-setup][netcup] ${DATA_DIR} is on the root filesystem; ${AVAIL_GB} GB available."
if [ "${AVAIL_GB}" -lt "${MIN_FREE_GB}" ]; then
  echo "[disk-setup][netcup][warn] Below ${MIN_FREE_GB} GB free. Data, Docker images and OS share one" >&2
  echo "[disk-setup][netcup][warn] filesystem here — exhausting it takes down the whole node." >&2
fi
```

---

## 6. Choosing the target from GitHub

This is the part you asked about specifically. The mechanism is a `workflow_dispatch` **choice** input bound to a
GitHub **Environment** of the same name; environment secrets shadow repository secrets automatically, so the body of
the workflow needs no changes at all beyond the `DEPLOY_ROOT` variable.

### 6.1 Workflow changes

```yaml
name: Deploy

on:
  workflow_dispatch:
    inputs:
      target:
        description: 'Deployment target'
        required: true
        type: choice
        options:
          - hetzner-prod
          - netcup-prod
        default: hetzner-prod
      image_tag:
        description: 'GHCR image tag to deploy (e.g. sha-abc1234)'
        required: true
        type: string

jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: ${{ inputs.target }}        # ← selects the secret set AND any approval gate
    concurrency:
      group: deploy-${{ inputs.target }}     # ← the two targets no longer block each other
      cancel-in-progress: false
    permissions:
      contents: read
    env:
      DEPLOY_ROOT: ${{ vars.DEPLOY_ROOT }}   # environment *variable*, not a secret
    steps:
      # ... every existing step unchanged; secrets.SSH_HOST / SSH_USER / SSH_DEPLOY_KEY /
      # SSH_KNOWN_HOST now resolve from the selected environment.
      # Replace the literal `/opt/skillars` in the four SSH command bodies with "${DEPLOY_ROOT}".
```

Why a `choice` input rather than a free-text one: the value is interpolated into `environment:` and into log/notification
strings, and `choice` constrains it to the allowlist at the API level. Keep the existing `image_tag` regex validation
step as-is.

### 6.2 GitHub Environments to create

**Settings → Environments →** `hetzner-prod` and `netcup-prod`.

| Kind | Name | Notes |
| :--- | :--- | :--- |
| Secret | `SSH_HOST` | Per-environment node IP |
| Secret | `SSH_USER` | Usually `root` on both |
| Secret | `SSH_DEPLOY_KEY` | **Distinct keypair per node** — do not reuse one key across providers |
| Secret | `SSH_KNOWN_HOST` | `ssh-keyscan -H <node-ip>` output for that node |
| Secret | `GHCR_PAT` | Same value is fine; scope stays `read:packages` |
| Variable | `DEPLOY_ROOT` | `/opt/skillars` on both today; exists so a differing path never means a code change |

Repository-level secrets (`SLACK_WEBHOOK_URL`, `SMTP_*`, `NOTIFY_EMAIL`) stay where they are — notifications are not
per-target. Add the target name to the Slack/email payloads so a message is unambiguous about which box moved.

Enable **required reviewers** on whichever environment is live production. That is the point of Environments beyond
secret scoping: the target selector becomes an approval gate, not just a dropdown.

### 6.3 Optional: deploy to both

If you end up running both nodes live (§10, Mode B), add `both` to the options and fan out:

```yaml
jobs:
  select:
    runs-on: ubuntu-latest
    outputs:
      targets: ${{ steps.pick.outputs.targets }}
    steps:
      - id: pick
        run: |
          if [ "${{ inputs.target }}" = "both" ]; then
            echo 'targets=["hetzner-prod","netcup-prod"]' >> "$GITHUB_OUTPUT"
          else
            echo 'targets=["${{ inputs.target }}"]' >> "$GITHUB_OUTPUT"
          fi
  deploy:
    needs: select
    strategy:
      fail-fast: false                       # one node failing must not abandon the other mid-deploy
      matrix:
        target: ${{ fromJson(needs.select.outputs.targets) }}
    environment: ${{ matrix.target }}
    concurrency:
      group: deploy-${{ matrix.target }}
    # ... same steps
```

Do not add this until you have decided on Mode B. `fail-fast: false` is not optional if you do — a half-deployed pair is
worse than either outcome alone.

### 6.4 Also target-aware

- `deploy/backup/install-crons.sh` — unchanged, but the crons it installs now include restic.
- `docs/deployment/deploy-guide.md` and `rollback.md` — add the target-selection step; the rollback procedure itself
  (SSH in, edit `APP_IMAGE`, `docker compose up -d --no-deps app`) is already identical on both.
- Consider extending the same `target` input to a `deploy-uat.yml` if UAT ever moves off its current box.

---

## 7. Compose, paths and alerting

### 7.1 Parameterise the two hard-coded roots

`docker-compose.yml` currently embeds `/opt/skillars/data/...` in five bind mounts and `/opt/skillars/traefik/acme.json`
in one. Neither is Hetzner-specific, but both bake in the assumption that the data root is a mount point. Replace with:

```yaml
- ${DATA_ROOT:-/opt/skillars/data}/postgres:/var/lib/postgresql/data
- ${DEPLOY_ROOT:-/opt/skillars}/traefik/acme.json:/etc/traefik/acme.json
```

Compose interpolates these from the `.env` beside the compose file — which is exactly where `/opt/skillars/.env` already
lives. Defaults preserve current behaviour, so this is a no-op on the existing Hetzner node.

### 7.2 New `.env` keys

```bash
# --- Deployment target ---
# 'hetzner' or 'netcup'. Selects deploy/providers/<value>/ plugins. Read by provision.sh,
# snapshot.sh and the restore scripts.
DEPLOY_PROVIDER=netcup
DEPLOY_ROOT=/opt/skillars
DATA_ROOT=/opt/skillars/data
```

### 7.3 One disk alert that works on both

Replace `DiskDataVolumeHigh` and `DiskRootHigh` in `deploy/lgtm/alerts.yml` with a single rule that selects whichever
filesystem holds the data, separate volume or not:

```yaml
- alert: DiskHigh
  expr: |
    max by (instance, mountpoint) (
      1 - (
        node_filesystem_avail_bytes{mountpoint=~"/|/opt/skillars/data", fstype!="tmpfs"}
        /
        node_filesystem_size_bytes{mountpoint=~"/|/opt/skillars/data", fstype!="tmpfs"}
      )
    ) > 0.75
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Disk usage on {{ $labels.mountpoint }} at {{ $value | humanizePercentage }}"
    runbook: "docker system prune -f; du -sh /opt/skillars/data/* ; check restic cache size"
```

Threshold drops from 0.80 to 0.75 because on netcup a full filesystem takes the OS down with the database, so the
warning needs more runway. `by (mountpoint)` keeps the alert readable when both filesystems exist (Hetzner).

Add a **backup-freshness** alert while you are in this file — with snapshots demoted, a silently failing `pg-backup.sh`
becomes the single point of failure in the DR story:

```yaml
- alert: BackupStale
  expr: time() - skillars_backup_last_success_timestamp_seconds > 32400   # 9h; crons run every 6h
  for: 10m
  labels: { severity: critical }
  annotations:
    summary: "No successful pg_dump in over 9 hours — RPO at risk"
```

This needs `pg-backup.sh` to write a Prometheus textfile metric on success and node_exporter to be started with
`--collector.textfile.directory`. Small change, disproportionate value.

### 7.4 Container log rotation and image churn

Already handled by the `x-logging` anchor (`max-size: 10m`, `max-file: 3`). On netcup, add a weekly
`docker image prune -af --filter "until=168h"` cron — on Hetzner, accumulated image layers ate root disk while data sat
safely on the volume; on netcup they compete with PostgreSQL for the same 256 GB.

---

## 8. Backup and DR strategy

The existing scheme is two-tier: 6-hourly `pg_dump` → object storage (RPO 6 h) and daily Hetzner Volume snapshots
(fast full-node recovery). Tier 2 has no netcup equivalent worth relying on. The fix is to replace it with something
provider-agnostic that is *better* on both.

### 8.1 Target scheme

| Tier | Mechanism | Covers | Frequency | Provider dependency |
| :--- | :--- | :--- | :--- | :--- |
| 1 | `pg-backup.sh` — `pg_dump` → S3 | Database, offsite, portable across providers | 6 h | **None** (any S3 endpoint) |
| 2 | `restic-backup.sh` — `/opt/skillars/data` → S3 | Full data tree: Postgres dir, Grafana, Loki, Tempo, Prometheus, `acme.json` | Nightly | **None** |
| 3 | Provider snapshot | Fast in-place rollback of a bad OS/config change | Daily where cheap | Hetzner: volume API. netcup: SCP, manual or workstation-scripted |

Tier 3 becomes **optional and best-effort on both providers**. Tiers 1 and 2 carry the RTO/RPO commitment.

### 8.2 `restic-backup.sh`

```bash
#!/usr/bin/env bash
# Nightly offsite backup of the whole data tree. Provider-agnostic: this is what replaces
# Hetzner Volume snapshots as the recovery-from-total-node-loss tier.
set -euo pipefail
. /opt/skillars/.env
export RESTIC_REPOSITORY="s3:${S3_BACKUP_ENDPOINT}/${S3_BACKUP_BUCKET}/restic"
export RESTIC_PASSWORD                      # from .env; 32+ random chars — LOSING THIS LOSES THE BACKUPS
export AWS_ACCESS_KEY_ID="${S3_BACKUP_ACCESS_KEY}"
export AWS_SECRET_ACCESS_KEY="${S3_BACKUP_SECRET_KEY}"

DATA_ROOT="${DATA_ROOT:-/opt/skillars/data}"

# Postgres is backed up logically by pg-backup.sh; excluding its data dir here avoids
# archiving a torn copy of a live cluster and keeps the repo small.
restic backup "${DATA_ROOT}" "${DEPLOY_ROOT:-/opt/skillars}/traefik/acme.json" \
  --exclude "${DATA_ROOT}/postgres" \
  --tag nightly --host "${DEPLOY_PROVIDER:-unknown}"

restic forget --keep-daily 7 --keep-weekly 4 --keep-monthly 6 --prune
restic check --read-data-subset=5%
```

Note the exclusion: taking a filesystem-level copy of a running PostgreSQL data directory produces an inconsistent
snapshot. The database's recovery path is Tier 1 (`restore-from-dump.sh`), which is already scripted and integrity-checked.
Tier 2 covers everything a `pg_dump` does not: dashboards, historical metrics and logs, and the ACME certificate store.

`RESTIC_PASSWORD` must be stored **outside** the node as well as in `/opt/skillars/.env` — a password that exists only
on the box it protects protects nothing. Put it in your password manager at the same time you generate it.

### 8.3 Where the offsite bucket lives

netcup offers no object storage, so this is a decision either way:

| Option | Note |
| :--- | :--- |
| **Hetzner Object Storage** (status quo) | Works unchanged from a netcup node — it is just an S3 endpoint. Keeps one Hetzner relationship alive; that is a *feature* for offsite-ness if compute moves to netcup. |
| Backblaze B2 / Wasabi / Cloudflare R2 | Cheap, S3-compatible, fully independent |
| Scaleway / OVH / IONOS | EU-resident, if GDPR data-residency is a stated requirement |
| Hetzner Storage Box | Cheapest per GB; SFTP/rclone rather than S3 — restic supports it natively, `pg-backup.sh` would need an rclone path |

**Recommendation:** keep the Hetzner Object Storage bucket regardless of where compute lands. The whole point of an
offsite backup is that it does not share a failure domain with the node, and "backups at provider A, compute at provider
B" is strictly better than both at one provider. No migration work, no new vendor.

### 8.4 Rename `HOS_*` → `S3_BACKUP_*`

`HOS` stands for Hetzner Object Storage, but the scripts are already provider-agnostic — `pg-backup.sh` and
`restore-from-dump.sh` only ever use `aws s3 --endpoint-url`. Rename for honesty, with a back-compat shim so the running
node does not break the moment the code lands:

```bash
S3_BACKUP_BUCKET="${S3_BACKUP_BUCKET:-${HOS_BUCKET:-}}"
S3_BACKUP_ENDPOINT="${S3_BACKUP_ENDPOINT:-${HOS_ENDPOINT:-}}"
S3_BACKUP_ACCESS_KEY="${S3_BACKUP_ACCESS_KEY:-${HOS_ACCESS_KEY:-}}"
S3_BACKUP_SECRET_KEY="${S3_BACKUP_SECRET_KEY:-${HOS_SECRET_KEY:-}}"
S3_BACKUP_PREFIX="${S3_BACKUP_PREFIX:-${HOS_BACKUP_PREFIX:-pg-backups/}}"
```

Drop the shim one release after both nodes carry the new keys.

### 8.5 Recovery procedures

| Scenario | Hetzner | netcup |
| :--- | :--- | :--- |
| Bad deploy | `deploy.yml` auto-revert, else `rollback.md` | Identical |
| Data corruption / bad migration | `restore-from-dump.sh latest` | Identical |
| Bad OS or config change | Volume snapshot revert | SCP snapshot revert (whole server; console procedure printed by `netcup/snapshot-restore.sh`) |
| **Total node loss** | Provision replacement → `restic restore` → `restore-from-dump.sh latest` → DNS | **Identical** ← this is the win |

RPO < 24 h (in practice 6 h) is preserved unconditionally. **RTO < 4 h is preserved only if a replacement node can be
purchased within the window** — which §2.2 shows is not currently guaranteed at Hetzner. Make that dependency explicit
in the runbook rather than leaving it as an assumption:

- Record a **primary and a fallback shape per provider** (e.g. Hetzner: CX33, falling back to CPX31 at ~2× price;
  netcup: VPS 1000 G12, falling back to the next tier or the Root Server line). Recovery targets 4 vCPU / 8 GB /
  Ubuntu / Docker — never a SKU.
- Add a first step to the total-node-loss procedure: **check stock, and if the primary shape is unavailable, order the
  fallback immediately.** Paying double for a week to restore service is the correct call; spending an hour of the RTO
  budget refreshing an order page is not.
- Because the two providers are unlikely to be capacity-constrained simultaneously, **the other provider is itself the
  fallback.** This is the strongest practical argument for the portability work in this document, over and above cost.

The quarterly restore drill mandated by `deployment-proposal.md` §3 now has one procedure to rehearse instead of two.
Rehearse it **onto the fallback shape at the other provider** — that single exercise validates the backup, the runbook,
the provider-plugin layer and the migration path at once.

---

## 9. Security posture

### 9.1 Equivalence

Both providers give a free network-level firewall enforced outside the guest. The three rules this stack needs
(80/tcp any, 443/tcp any, 22/tcp restricted) are well inside netcup's 500-rule ceiling, and its TCP-only statefulness is
irrelevant — every inbound service here is TCP. `ufw` (`provision.sh` §5) stays as defence in depth on both, as does SSH
hardening and fail2ban (§3–4), all of which are already provider-agnostic.

The netcup firewall's lack of a REJECT action is a non-issue: DROP is the correct behaviour for an internet-facing
edge anyway.

### 9.2 Firewall as code becomes firewall as verification

Hetzner's firewall is applied from CI-adjacent tooling with a bearer token. netcup's is not — the SCP REST API's
device-code OAuth2 flow is designed for humans, and netcup SCP credentials should never enter GitHub Actions. Rather
than accept asymmetric guarantees, invert the model:

- `deploy/firewall/firewall-rules.json` becomes the **provider-neutral declarative source of truth** — the same three
  rules, with a `providers` block noting how each is applied.
- `deploy/providers/hetzner/firewall-apply.sh` — applies them (existing script, moved).
- `deploy/providers/netcup/firewall-apply.sh` — prints the exact SCP procedure and blocks for confirmation; optionally
  drives `netcup-scp-cli` from an operator workstation with a cached refresh token.
- `deploy/firewall/verify-firewall.sh` — **new, provider-agnostic.** Probes the node's public IP from outside: expects
  80/443 open, 22 reachable only from the allowlisted source, and a sample of other ports closed. Run it from CI on a
  schedule and after every provisioning run.

Verification catches drift on *both* providers, including drift `apply-firewall.sh` would miss because someone changed a
rule in the console. It is a better guarantee than apply-only, and it is the only guarantee available on netcup.

### 9.3 Other differences

- **Distinct SSH keypairs per node.** One compromised deploy key must not open both providers. This is why
  `SSH_DEPLOY_KEY` is an environment secret in §6.2, not a repository secret.
- **rDNS.** Set PTR records in SCP for the netcup IPv4/IPv6. The app relays mail through an external SMTP provider, so
  this is hygiene rather than deliverability-critical, but it is free.
- **DDoS protection.** Hetzner advertises always-on DDoS protection. netcup's public documentation does not state an
  equivalent. Ask netcup sales before committing to a 12-month term if this matters to you.
- **No netcup credentials in the repo or in Actions.** All netcup control-plane operations are operator-workstation
  actions.

---

## 10. Operating model — pick one

| | **Mode A — Portable single production** | **Mode B — Two live environments** |
| :--- | :--- | :--- |
| Shape | One live node; the other provider exists as a tested, documented, rehearsed destination | e.g. netcup = production, Hetzner = UAT and warm DR |
| Cost | ~€12–18/mo | ~€25–30/mo |
| DR | Rebuild-from-backup, RTO hours | Warm standby, RTO minutes with DNS failover |
| Extra work | None beyond §5–§9 | Data replication or accepted staleness; §6.3 matrix; two TLS certs; doubled patching |

**Recommendation: Mode A now.** Its mechanics (plugin layer, environment-selected deploys, provider-agnostic restic
restore) are a strict prerequisite for Mode B anyway, so nothing is wasted if you upgrade later. At the current scale
(<50 concurrent users, RTO < 4 h), a rehearsed rebuild meets the stated objective and a second live node does not earn
its cost.

### Bake plan before committing

netcup's discounted price requires a 12-month term. The hourly tariff costs more per month but has no minimum term
beyond a 6-month prepayment that is refundable pro-rata — cheap insurance against discovering the shared-vCore problem
in month two.

1. Order VPS 1000 G12 **on the hourly tariff**, Nuremberg.
2. Provision and deploy against a staging FQDN (`netcup.<your-domain>`), production DNS untouched.
3. Run 2–4 weeks: restore a production `pg_dump` into it, replay realistic load, watch `steal` time in node_exporter
   (`node_cpu_seconds_total{mode="steal"}` — the direct measurement of noisy neighbours), p95 latency, and JVM GC pauses.
4. Rehearse a full restore from restic + dump. Time it against the 4 h RTO.
5. Then decide: switch to the 12-month term, move to the Root Server line, or stay on Hetzner. Any outcome is a win —
   the portability work stands either way.

---

## 11. Provisioning a netcup VPS — runbook

To become `docs/deployment/first-time-setup-netcup.md`, mirroring the existing
[`first-time-setup.md`](../../docs/deployment/first-time-setup.md) step for step. Steps 5–9 are **verbatim identical**
to the Hetzner guide, which is the point of this whole proposal.

### Step 1 — Order and install

1. netcup CCP → order **VPS 1000 G12**, tariff **hourly** for the bake (12-month afterwards), location **Nuremberg**.
   Confirm any one-off setup fee at checkout.
2. SCP → **SSH keys** → add your public key **before** OS installation, so the image is provisioned with it.
3. SCP → **Media / Images** → install **Ubuntu 24.04 LTS** (or 22.04 to match `provision.sh`'s stated target — pick one
   and record it; `provision.sh` works on both).
4. Note the IPv4 and IPv6 addresses. Set **rDNS** for both.
5. Verify key-only login: `ssh root@<ip>`. Do not proceed until this works — Step 3 disables password auth.

### Step 2 — DNS

Identical to the Hetzner guide: `A`/`AAAA` for `DOMAIN` and `MONITORING_DOMAIN` → the netcup IPs.
**Lower the TTL to 60 s at least 24 h before any cutover** (§12).

For the bake, point a *staging* FQDN at the netcup box and leave production DNS alone.

### Step 3 — Provision

```bash
ssh root@<netcup-ip>
git clone <repo> /opt/skillars
cd /opt/skillars
DEPLOY_PROVIDER=netcup bash deploy/provision.sh
```

Expect the disk step to report that data lives on the root filesystem — that is correct, not a warning to fix.

### Step 4 — Firewall

SCP → **Firewall** → new policy, attached to the public interface:

| Direction | Protocol | Port | Source | Action |
| :--- | :--- | :--- | :--- | :--- |
| INGRESS | TCP | 80 | `0.0.0.0/0`, `::/0` | ACCEPT |
| INGRESS | TCP | 443 | `0.0.0.0/0`, `::/0` | ACCEPT |
| INGRESS | TCP | 22 | see §3.1 decision | ACCEPT |
| INGRESS | TCP/UDP | any | any | DROP (default) |
| EGRESS | any | any | any | ACCEPT |

Egress must stay open: the app needs outbound access for SMTP, Bunny.net, Stripe, Let's Encrypt and GHCR — the same
constraint noted on the `skillars-internal` network in `docker-compose.yml`.

Then: `bash deploy/firewall/verify-firewall.sh <netcup-ip>`.

### Step 5 — Secrets

Copy `.env` from the Hetzner node (or build from `.env.example`), then change:

```diff
+DEPLOY_PROVIDER=netcup
+DEPLOY_ROOT=/opt/skillars
+DATA_ROOT=/opt/skillars/data
+RESTIC_PASSWORD=<openssl rand -base64 32 — ALSO STORE IN YOUR PASSWORD MANAGER>
-HCLOUD_TOKEN=...
-HETZNER_VOLUME_ID=...
 # S3_BACKUP_* unchanged — the bucket does not move (§8.3)
 DOMAIN=netcup.<your-domain>          # staging FQDN during the bake
```

`chmod 600 /opt/skillars/.env`, then re-run `provision.sh` to have it enforce permissions and create `acme.json`.

### Step 6 — Deploy

```bash
cd /opt/skillars && docker compose up -d
```

Or, once the GitHub Environment exists, run the **Deploy** workflow with `target: netcup-prod`.

### Steps 7–9 — Verify, monitor, back up

Identical to the Hetzner guide: health check, UptimeRobot monitor against the new FQDN, Grafana at `MONITORING_DOMAIN`,
`bash deploy/backup/install-crons.sh`. Then verify a backup actually lands:

```bash
bash deploy/backup/pg-backup.sh && bash deploy/backup/restic-backup.sh
restic snapshots     # must list the run you just made
```

A backup system is not installed until you have seen a restore work. Schedule the first drill for week one of the bake,
not the first quarter.

---

## 12. Cutover — Hetzner → netcup

Only relevant if the bake succeeds and you decide to move.

**Prerequisite — Let's Encrypt.** Traefik uses the HTTP-01 challenge (`deploy/traefik/traefik.yml`), so a certificate
for the production `DOMAIN` **cannot** be issued on the netcup box until DNS already points there. Two consequences:

- There is an unavoidable few-second window at cutover between the DNS flip and certificate issuance. At <50 concurrent
  users during a maintenance window this is acceptable. If it is not, add a DNS-01 resolver so the certificate can be
  pre-warmed.
- **Use the Let's Encrypt staging CA for rehearsals.** Production LE allows 5 duplicate certificates per week; a couple
  of cutover practice runs will exhaust it and lock you out of the real thing. Add a `caServer` toggle to `traefik.yml`
  for this.

**Sequence:**

1. **T-24 h** — DNS TTL to 60 s. Announce the window. Confirm restic and dump backups are current on the Hetzner node.
2. **T-1 h** — Deploy the exact production image tag to netcup. Restore the latest production dump. Verify with the
   staging FQDN.
3. **T-0** — Stop `app` on Hetzner (`docker compose stop app`; Postgres stays up). Take a final `pg-backup.sh`.
4. Run `restore-from-dump.sh latest` on netcup. Verify table counts and `/manage/health`.
5. Change `DOMAIN` in the netcup `.env` to the production FQDN; `docker compose up -d`.
6. Flip DNS `A`/`AAAA` to the netcup IPs. Watch Traefik logs for ACME issuance.
7. Verify end to end: login, a booking flow, a payment in Stripe test mode, a video playback URL, Grafana.
8. Update the `netcup-prod` GitHub Environment to production status; enable required reviewers. Point UptimeRobot at the
   new node if it monitors by IP (it monitors by FQDN today — no change needed).
9. **T+7 d** — Keep the Hetzner node running and reachable as rollback. Only then decommission, and take a final volume
   snapshot before you do.
10. Restore DNS TTL to normal.

**Things that do *not* need changing** because they are keyed on the domain rather than the IP: Stripe webhooks,
Bunny.net webhooks and CDN configuration, UptimeRobot, OAuth redirect URIs, Grafana's root URL.

**Things that do:** anything with an IP allowlist at a third party, and outbound SMTP reputation from a new IP — send a
low volume for the first few days and watch bounce rates.

---

## 13. Implementation plan

| Phase | Work | Files | Acceptance |
| :--- | :--- | :--- | :--- |
| **0** | Fix the live defects and stale plan names (§3.1–§3.4) | `firewall-rules.json`, `apply-firewall.sh`, `alerts.yml`, `deployment-proposal.md` §1/§3/§4/§5 | Firewall docs match reality; disk alert fires in a `df`-filling test; no deprecated plan name survives in `requirements/deployment/` |
| **1** | Plugin skeleton; move Hetzner scripts under `providers/hetzner/`; `DEPLOY_PROVIDER` dispatch in `provision.sh` | `deploy/providers/**`, `provision.sh` | Re-running `provision.sh` on the existing Hetzner node is a clean no-op |
| **2** | Parameterise paths | `docker-compose.yml`, `.env.example`, `secrets-reference.md` | `docker compose config` output byte-identical to today with defaults |
| **3** | `restic-backup.sh`, `restore-from-restic.sh`, `S3_BACKUP_*` rename + shim, backup-freshness metric and alert | `deploy/backup/**`, `alerts.yml`, `prometheus.yml` | Restic restore into a scratch dir verified on the Hetzner node; `BackupStale` fires when the cron is disabled |
| **4** | Target-selectable deploy | `.github/workflows/deploy.yml`, two GitHub Environments | Workflow with `target: hetzner-prod` deploys exactly as before |
| **5** | netcup plugin + `verify-firewall.sh` | `deploy/providers/netcup/**`, `deploy/firewall/verify-firewall.sh` | Firewall verify passes against both nodes |
| **6** | Docs | `first-time-setup-netcup.md`, updates to `deploy-guide.md`, `rollback.md`, `backup-restore.md`, `secrets-reference.md`, `monitoring.md` | A fresh netcup node reaches healthy following only the written guide |
| **7** | Bake (§10) | — | 2–4 weeks of clean steal-time and p95; one timed full-restore drill inside RTO |

Phases 0–4 deliver value with or without netcup: they remove Hetzner from the DR path, fix a firewall/CI contradiction,
fix a dead alert, and add an approval gate to production deploys. Phases 5–7 are the netcup-specific increment.

---

## 14. Open questions

1. **Mode A or Mode B** (§10)? Everything else assumes A.
2. **SSH exposure** (§3.1) — accept port 22 open with key-only auth and fail2ban, or invest in pull-based deploy now?
3. **Data residency.** Does Skillars have a stated EU-residency requirement? It constrains the netcup datacentre and
   the backup bucket, and it is cheaper to decide now than to migrate a bucket later.
4. **DDoS protection** — is netcup's posture acceptable, or does it need a written answer from their sales team before a
   12-month term?
5. **Shared vs guaranteed CPU** — is the Root Server line's price premium pre-approved if the bake shows steal time?
6. **UAT** — does UAT move to netcup too, or stay where it is? The `target` input extends to it trivially, but it is out
   of scope until asked.
7. **Backup bucket** — confirm keeping Hetzner Object Storage (§8.3 recommendation) versus moving to a third party.
8. **Is there already a running Hetzner node, and is it on legacy pricing?** Hetzner's June 2026 adjustment grandfathers
   existing servers until an account change moves them. If a CX32 is live at the old rate, that rate is **not
   recoverable** — decommissioning it and later returning to Hetzner means re-entering at CX33 new-order prices. That
   raises the cost of a reversible experiment and is an argument for keeping the Hetzner node alive through the bake
   (§10) rather than migrating first and evaluating after.
9. **Which Hetzner location is the console project set to?** If it is a US or APAC region, the CX and CAX lines are not
   offered there at all (§2.1) — moving the project to `eu-central` is a prerequisite for seeing them, though §2.2
   means they may still show as unavailable once you do. Interacts with open question 3 (data residency).
10. **What is the approved fallback shape and price ceiling for an emergency rebuild?** §8.5 needs a pre-authorised
    answer — "order CPX31 at ~€16.49 rather than wait for CX33 stock" is a decision that must be made before the
    incident, not during it.

---

## Sources

- [netcup VPS 1000 G12 product page](https://www.netcup.com/de/server/vps/vps-1000-g12-12m)
- [netcup Help Center — Server firewall](https://netcup.com/en/helpcenter/documentation/server/firewall)
- [netcup Help Center — Local Block Storage](https://netcup.com/en/helpcenter/documentation/server/local-block-storage)
- [netcup Help Center — REST API](https://netcup.com/en/helpcenter/documentation/server/rest-api)
- [netcup Help Center — Server general](https://netcup.com/en/helpcenter/documentation/server/general)
- [netcup Community — Questions about the server Snapshots feature](https://forum.netcup.de/thread/22421-questions-about-the-server-snapshots-feature/)
- [netcup-scp-cli — SCP REST API capabilities and auth](https://github.com/pavelpikta/netcup-scp-cli)
- [netcup snapshots & the Server Control Panel](https://serverkueche.de/en/tutorials/netcup-snapshots-scp/)
- [LowEndTalk — netcup hourly billing](https://lowendtalk.com/discussion/181392/netcup-hourly-billing-questions)
- [Hetzner Docs — Price adjustment 15 June 2026](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/) (authoritative for the old/new price table in §2.1)
- [Hetzner Cloud — Cost-optimized (CX) plans and `eu-central` availability](https://www.hetzner.com/cloud/cost-optimized/)
- [Hetzner pressroom — new shared vCPU cloud servers (Gen3 CX line)](https://www.hetzner.com/pressroom/new-cx-plans/)
- [VPS for Devs — CX32 deprecated, CX33 replacement and pricing](https://vpsfor.dev/posts/hetzner-cx32-vs-cx33-2026/) (secondary source; the €8.49 CX33 figure needs console confirmation)
- [webhosting.today — Hetzner's Limited ("-1-Ltd") tier and the three 2026 price changes](https://webhosting.today/2026/05/29/hetzner-has-now-raised-prices-three-times-in-2026-this-one-is-different/)
- [Server Radar — Hetzner Cloud availability & stock tracker](https://radar.iodev.org/cloud-status) — **add this to the DR runbook**; it is how you check stock before committing to a rebuild plan (§8.5)
- [Hetzner Cloud Radar](https://hetzner.thegoated.dev/) — second availability tracker

> **Verification note.** Hetzner's own pricing and availability tables render client-side and could not be read
> directly. The old/new plan prices in §2.1 come from Hetzner's official price-adjustment documentation; the CX33
> headline price, the CPX31 price, the per-GB volume/snapshot rates and the stock status in §2.2 come from secondary
> sources and **secondary sources disagree on the CX33 figure** (§2.4). Confirm all of them in the Hetzner Console
> before this document is used to justify a spend decision. Availability in particular is a snapshot — §2.2 will age
> faster than any other section here, and its conclusions should be re-checked at purchase time.
