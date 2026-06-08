# Traefik and TLS Reference

This guide covers how Traefik is configured, how TLS certificates are issued and renewed, and what
to do if a certificate fails to renew. It is the reference for FR-26: Traefik and TLS configuration.

---

## Configuration Files

Traefik v3.3 is configured from two sources:

| File | Purpose |
|---|---|
| `deploy/traefik/traefik.yml` | Static config: entry points, ACME resolver, logging, ping |
| `docker-compose.yml` labels on `app` and `grafana` services | Dynamic routing config: which requests route to which containers |
| `/opt/skillars/traefik/acme.json` on the Node | ACME certificate storage — not in the repository; auto-created by Traefik on first run |

**Dashboard:** The Traefik dashboard is disabled (`api.dashboard: false` in `traefik.yml`). There is no `/dashboard` endpoint. The Traefik API port (8080) is not exposed on the host network.

---

## Entry Points

| Entry Point | Port | Behaviour |
|---|---|---|
| `web` | `:80` | Redirects all HTTP requests to HTTPS permanently (301) |
| `websecure` | `:443` | Terminates TLS; forwards to the appropriate container |

All public traffic enters through these two entry points. No other ports are exposed by Traefik.

---

## TLS Certificate Issuance

Traefik uses Let's Encrypt with the **HTTP-01 challenge** to issue certificates automatically.

How it works:

1. On startup, Traefik reads or creates `/opt/skillars/traefik/acme.json`
2. For any domain that needs a certificate, Traefik requests one from Let's Encrypt
3. Let's Encrypt verifies the domain by sending an HTTP request to port 80 — the `web` entry point must be reachable during this challenge
4. Let's Encrypt issues the certificate; Traefik stores it in `acme.json`
5. Traefik loads the certificate and begins serving HTTPS traffic

The ACME account email is sourced from `LETSENCRYPT_EMAIL` in `/opt/skillars/.env`. It is injected via a CLI argument in `docker-compose.yml` at container start (Traefik's static config file does not interpolate environment variables).

> **Note:** If port 80 is blocked at the Hetzner firewall during the challenge, issuance fails. The firewall rules at `deploy/firewall/firewall-rules.json` must allow inbound TCP 80.

---

## Renewal Timeline

| Event | Timing |
|---|---|
| Certificate validity | 90 days |
| Traefik renewal check interval | Every 24 hours |
| Traefik initiates renewal | When fewer than 30 days remain |
| No operator action required | Renewal is fully automatic under normal conditions |

Under normal operation, Traefik renews certificates without any intervention. Certificates should never reach expiry unless Traefik is stopped or port 80 is blocked.

---

## Checking the Current Certificate Status

Run these commands from the Node:

```bash
# Connect to the Node:
ssh <SSH_USER>@<SSH_HOST>

# Load environment variables to set ${DOMAIN}:
source /opt/skillars/.env

# Check certificate expiry dates via TLS handshake:
echo | openssl s_client -servername ${DOMAIN} -connect ${DOMAIN}:443 2>/dev/null \
  | openssl x509 -noout -dates

# Expected output:
# notBefore=May 25 00:00:00 2026 GMT
# notAfter=Aug 23 23:59:59 2026 GMT
```

Alternatively, count the certificates stored in `acme.json` (may be empty if Traefik just started):

```bash
sudo python3 -c "
import json
d = json.load(open('/opt/skillars/traefik/acme.json'))
certs = d.get('letsencrypt', {}).get('Certificates', [])
print(len(certs), 'certificate(s) stored')
"
```

---

## What to Do If a Certificate Fails to Renew

All `docker compose` commands in this section must be run from `/opt/skillars` (the project root):

```bash
cd /opt/skillars
```

### Scenario A: Traefik is running but the certificate is not renewing

1. Check Traefik logs for ACME errors:

```bash
docker compose logs --tail=100 traefik | grep -i acme
```

2. Verify port 80 is still allowed at the Hetzner firewall:

```bash
cat deploy/firewall/firewall-rules.json
# Confirm an inbound TCP rule for port 80 exists
```

3. If the firewall was modified, reapply the rules:

```bash
bash deploy/firewall/apply-firewall.sh
```

4. Restart Traefik to trigger a renewal check:

```bash
docker compose restart traefik
```

> **Note:** Traefik only attempts renewal when fewer than 30 days remain on the current certificate. If the certificate has more than 30 days remaining, the restart will not produce a renewal — the expiry date after restart will be unchanged. In that case, no action is needed until the 30-day window is reached.

5. Wait 60 seconds, then recheck the certificate expiry:

```bash
echo | openssl s_client -servername ${DOMAIN} -connect ${DOMAIN}:443 2>/dev/null \
  | openssl x509 -noout -dates
```

---

### Scenario B: acme.json is empty or corrupt

This can happen after a volume restore or accidental deletion of the file.

1. Stop Traefik:

```bash
docker compose stop traefik
```

2. Optionally back up the existing `acme.json` before deleting it:

```bash
sudo cp /opt/skillars/traefik/acme.json /opt/skillars/traefik/acme.json.bak
```

3. Delete the stale `acme.json`:

```bash
sudo rm -f /opt/skillars/traefik/acme.json
```

4. Start Traefik — it creates a fresh `acme.json` on startup and immediately requests new certificates:

```bash
docker compose start traefik
```

5. Wait 2–3 minutes for Let's Encrypt to complete the HTTP-01 challenge and issue the certificate.

6. Verify:

```bash
echo | openssl s_client -servername ${DOMAIN} -connect ${DOMAIN}:443 2>/dev/null \
  | openssl x509 -noout -dates
```

---

### Scenario C: Let's Encrypt rate limit hit

Let's Encrypt enforces a limit of 5 failed validation attempts per hostname per hour. Repeated issuance requests for the same domain can trigger a weekly duplicate-certificate cap. The exact lockout duration depends on which limit was hit — it may be 1 hour or up to 1 week.

If you are hitting a rate limit (indicated by ACME error logs mentioning "rate limit" or "too many certificates"):

1. Stop restarting Traefik — each restart consumes another attempt and can extend the lockout
2. Wait at least 1 hour before retrying; if the limit persists after 1 hour, wait 24 hours
3. After the wait, restart Traefik once to trigger a fresh attempt

> **Important:** Do not add a Let's Encrypt staging flag to the production configuration. The staging environment issues untrusted certificates and would break production HTTPS traffic for all users.
