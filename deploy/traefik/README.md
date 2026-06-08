# Traefik Pre-Flight Checklist

Complete these steps on the Node **before** running `docker compose up -d` for the first time.

## 1. `acme.json` (automated by `provision.sh`)

`deploy/provision.sh` automatically creates `/opt/skillars/traefik/acme.json` with mode 600
during provisioning. No manual step required.

If you need to recreate it manually:
```bash
mkdir -p /opt/skillars/traefik && chmod 700 /opt/skillars/traefik && touch /opt/skillars/traefik/acme.json && chmod 600 /opt/skillars/traefik/acme.json
```

## 2. Place `.env` on the Node

```bash
# Copy your .env.example, fill in all values, then:
scp .env root@<NODE_IP>:/opt/skillars/.env
ssh root@<NODE_IP> "chmod 600 /opt/skillars/.env"
```

Required vars: `DOMAIN`, `LETSENCRYPT_EMAIL`, `APP_IMAGE`, `POSTGRES_*`, `JWT_SECRET`.

## 3. Confirm DNS points to the Node

`DOMAIN` must resolve to the Node's public IP before the first stack start, or the ACME HTTP-01 challenge will fail and no certificate will be issued.

## 4. Start the stack

```bash
docker compose up -d
docker compose ps          # all services should be healthy
docker compose logs traefik --tail=50
```

## Configuration Notes

- `traefik.yml` — static config (log level, entrypoints, ACME storage path, Docker provider).
  Traefik does **not** interpolate environment variables in this file; `LETSENCRYPT_EMAIL` is
  passed via the `command:` arg in `docker-compose.yml`.
- Dynamic config — sourced entirely from Docker container labels. No separate dynamic config file.
- Dashboard is disabled (`api.dashboard: false`) per FR-13.
- **Disk Monitoring:** Ensure host-level monitoring is configured for `/opt/skillars/data/postgres` to alert on low disk space, as PostgreSQL data is stored directly on the host volume.

Full usage documentation: `docs/deployment/first-time-setup.md`.
