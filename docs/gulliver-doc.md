# Gulliver — Server Reference (Hostwinds)

Reference for how `gulliver` is deployed and run on the Hostwinds Ubuntu
server, reconstructed from `docs/gulliver-history.txt` (raw `history`
output) and confirmed against the actual nginx/postgresql/ufw config content.

- **Server**: `hwsrv-1301707.hostwindsdns.com`
- **App**: Spring Boot fat jar, run directly with `java -jar`, no container
- **App directory**: `/server/gulliver/`
- **Jar**: `gulliver-0.0.1-SNAPSHOT.jar`
- **Profile**: `local` (`-Dspring.profiles.active=local`)
- **Port**: `9990` — confirmed (both by the nginx `proxy_pass` target and the
  embedded Tomcat temp dir name, see [Ports](#ports))
- **Reverse proxy**: nginx, plain HTTP (no TLS), one site config per app
  under `/etc/nginx/sites-available/`
- **Database**: PostgreSQL 17, same server, listening on all interfaces but
  not reachable from outside — blocked by `ufw`, see
  [Security note](#security-note-postgresql-listens-on-all-interfaces)
- **Logs**: `/var/log/gulliver/spring.log` (app log), plus
  `/server/gulliver/nohup.out` (stdout/stderr of the `nohup` wrapper itself)

---

## Quick reference — the commands you'll actually reuse

```bash
# Is it running?
ps auxw | grep gulliver-0.0.1-SNAPSHOT.jar

# Watch the app's own log
tail -f /var/log/gulliver/spring.log

# Stop it (find the PID from `ps auxw` above, then)
kill <PID>

# Start it (from /server/gulliver/)
cd /server/gulliver
nohup java -jar -Dspring.profiles.active=local gulliver-0.0.1-SNAPSHOT.jar &

# nginx: after editing a site config
nginx -t && sudo systemctl reload nginx

# Postgres: quick check
pg_isready
psql -U postgres

# Firewall status
ufw status
```

**There is no systemd service / process manager for gulliver** — the app is
started by hand with `nohup ... &` and does **not** come back up on its own
after a server reboot or crash (see
[Open questions](#open-questions--things-worth-deciding-later) if you want
that changed later).

---

## 1. Base system packages

```bash
apt update
apt upgrade
apt install openjdk-17-jdk-headless -y
java --version
```

## 2. PostgreSQL 17

Installed from the official PostgreSQL apt repo (Ubuntu's default repo only
ships an older major version):

```bash
sudo sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'
curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/postgresql.gpg
sudo apt update
sudo apt install postgresql-17
sudo systemctl start postgresql
sudo systemctl enable postgresql
```

Auth was then loosened for local access and opened up for network access —
**see the [Security note](#security-note-postgresql-listens-on-all-interfaces)
below, this is the part most worth double-checking**:

```bash
# host-based (network/TCP) connections: ident -> md5 (password required)
sed -i '/^host/s/ident/md5/' /etc/postgresql/17/main/pg_hba.conf

# local (unix socket) connections: peer -> trust (no password needed at all —
# this is why `psql -U postgres` works without a password prompt when run
# on the box itself)
sudo sed -i '/^local/s/peer/trust/' /etc/postgresql/17/main/pg_hba.conf

# allow ANY IP address to connect, to ANY database, as ANY user, given the
# correct md5 password
echo "host all all 0.0.0.0/0 md5" | sudo tee -a /etc/postgresql/17/main/pg_hba.conf

sudo systemctl restart postgresql
```

`postgresql.conf` was also edited by hand (`vim`) — confirmed: the only
meaningful change from the Debian/Ubuntu packaged default is

```
listen_addresses = '*'   # default is 'localhost'
```

Everything else in that file (`port = 5432`, `ssl = on` with the
auto-generated snakeoil self-signed cert, `cluster_name`, locale/logging
settings, `include_dir = 'conf.d'`) is the stock packaged default, not a
deliberate change made while setting up gulliver.

Verify:
```bash
pg_isready
psql -U postgres
```

### Security note: PostgreSQL listens on all interfaces

`pg_hba.conf` allows connections from **any IP on the internet** (password
required, md5), and `listen_addresses = '*'` means Postgres really is
listening on the public network interface, not just `localhost` — so
`pg_hba.conf` alone would make this genuinely internet-reachable.

**Confirmed not actually reachable today**, though: `ufw status verbose`
shows

```
Default: deny (incoming), allow (outgoing), disabled (routed)

To                         Action      From
--                         ------      ----
22/tcp                     ALLOW IN    Anywhere
80,443/tcp (Nginx Full)    ALLOW IN    Anywhere
22/tcp (v6)                ALLOW IN    Anywhere (v6)
80,443/tcp (Nginx Full (v6)) ALLOW IN    Anywhere (v6)
```

— default-deny incoming, and no rule for `5432`. The firewall is the only
thing standing between this Postgres instance and the open internet; the
Postgres-level config (`listen_addresses`/`pg_hba.conf`) would happily
accept the connection if it ever got there. That's a fragile way to be safe
— if `ufw` is ever disabled, reset, or a `5432` rule gets added later (e.g.
while debugging a second app's DB connectivity), the `0.0.0.0/0 md5` rule in
`pg_hba.conf` is immediately live with no other layer behind it. Worth
tightening `pg_hba.conf` to the specific IPs/subnets that actually need
network access (or removing the `0.0.0.0/0` line entirely if every app on
this box only ever talks to Postgres over `localhost`, which is the case for
gulliver today) rather than relying solely on the firewall — flagging rather
than changing it here, since that's a real behavior change, not just
documentation.

## 3. Directory layout

```bash
mkdir -p /var/log/gulliver     # app log directory
mkdir /server                  # top-level directory for all apps on this box
mkdir /server/gulliver         # gulliver's app directory — jar lives here
```

`/server/` (not `/opt/`, which is what Skillars' own deployment docs use) is
the convention already established on this box for app directories. Worth
following the same pattern for any additional app to keep things
predictable — e.g. `/server/skillars/`.

## 4. nginx reverse proxy

```bash
apt install nginx
```

One site config per app, under `/etc/nginx/sites-available/`, symlinked
into `/etc/nginx/sites-enabled/`:

```bash
vim /etc/nginx/sites-available/gulliver
sudo ln -s /etc/nginx/sites-available/gulliver /etc/nginx/sites-enabled/
nginx -t && sudo systemctl reload nginx
```

**The history shows an abandoned attempt at a single combined
`/etc/nginx/sites-available/multiple-apps` config** (created, edited
several times, then deleted from both `sites-available` and
`sites-enabled`, reverting back to the per-app `gulliver` file). Read as:
**one site config file per app is the pattern that was actually settled on**
— for a second app, create `/etc/nginx/sites-available/skillars` rather
than trying to fold both apps into one file again.

Confirmed contents of `/etc/nginx/sites-available/gulliver`:

```nginx
server {
    listen 80;
    server_name hwsrv-1301707.hostwindsdns.com;  # Replace with your domain or use IP
    location / {
        proxy_pass http://localhost:9990;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Optional: Timeout settings
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # Optional: Static file handling if needed
    location /static/ {
        # Configure static file directory if applicable
    }
}
```

**Plain HTTP only — `listen 80`, no TLS/certbot config, no `listen 443`
block.** `hwsrv-1301707.hostwindsdns.com` is served unencrypted today. If a
second app also needs to be reachable by hostname over HTTPS, that's a
separate piece of work (e.g. certbot + a `listen 443 ssl` block per site, or
a shared TLS-terminating reverse proxy in front of both) — not something
either app's own site config does today.

The unused `location /static/` block is a leftover template stub (no
`root`/`alias` directive, so it currently falls through and gets handled the
same as any other path) — safe to ignore or delete, not something gulliver
actually relies on.

## 5. Firewall (ufw)

```bash
apt install ufw -y
sudo ufw enable
ufw allow ssh
ufw allow 'Nginx Full'   # opens 80 + 443
ufw status
```

Confirmed current state (`ufw status verbose`):

```
Status: active
Logging: on (low)
Default: deny (incoming), allow (outgoing), disabled (routed)
New profiles: skip

To                         Action      From
--                         ------      ----
22/tcp                     ALLOW IN    Anywhere
80,443/tcp (Nginx Full)    ALLOW IN    Anywhere
22/tcp (v6)                ALLOW IN    Anywhere (v6)
80,443/tcp (Nginx Full (v6)) ALLOW IN    Anywhere (v6)
```

Only SSH and nginx's HTTP/HTTPS ports are explicitly allowed, IPv4 and
IPv6 — nothing else (including Postgres's 5432) is opened up, and the
default-deny-incoming policy covers everything not explicitly listed. See
the [Security note](#security-note-postgresql-listens-on-all-interfaces)
above for why this matters. Worth remembering when adding a second app:
whatever port it listens on internally (proxied by nginx) doesn't need its
own `ufw` rule, since nginx is the only thing that needs to reach it and
that's all on `localhost` — only add a new `ufw allow` if something needs to
be reached directly, bypassing nginx.

## 6. Running the app

Early on, the app was run in the foreground to test it directly (useful for
watching startup errors live, but the process dies the moment the SSH
session disconnects):

```bash
cd /server/gulliver
java -jar -Dspring.profiles.active=local gulliver-0.0.1-SNAPSHOT.jar
```

The actual persistent way it's run is backgrounded with `nohup`, so it
survives the SSH session ending (but, again, **not** a reboot — there's no
systemd unit):

```bash
cd /server/gulliver
nohup java -jar -Dspring.profiles.active=local gulliver-0.0.1-SNAPSHOT.jar &
```

To restart: find the PID and kill it, then re-run the `nohup` command above.
`nohup.out` in `/server/gulliver/` was deleted (`rm nohup.out`) at least once
between runs — worth doing before each restart if you don't want old output
mixed with new.

```bash
ps auxw | grep gulliver
kill <PID>
rm -f /server/gulliver/nohup.out   # optional, avoids mixing old/new output
nohup java -jar -Dspring.profiles.active=local gulliver-0.0.1-SNAPSHOT.jar &
```

## 7. Logs

| Location | What it is |
|---|---|
| `/var/log/gulliver/spring.log` | The app's own log output (Spring Boot's logging config writes here — implies `application-local.yml`/`application.properties` inside the jar sets `logging.file.name`) |
| `/server/gulliver/nohup.out` | Raw stdout/stderr captured by the `nohup` wrapper — mostly useful if the app fails before its own logging initializes |
| `/var/log/nginx/error.log` | nginx-side errors (proxy failures, bad gateway, etc.) — checked directly with `less` during troubleshooting |

```bash
tail -f /var/log/gulliver/spring.log
less /var/log/nginx/error.log
```

## Ports

No `-Dserver.port=...` flag is ever passed to the `java -jar` command, so
gulliver runs on whatever port is baked into the jar's default config —
confirmed as **9990** two ways: the nginx `proxy_pass http://localhost:9990`
target above, and (found first, before the nginx config was available) the
history shows poking around
`/tmp/tomcat.9990.6502699915484931074/work/Tomcat/localhost/ROOT/` — Spring
Boot's embedded Tomcat names its temp work directory `tomcat.<port>.<random>`.

**This matters directly for running a second app on this box**: Skillars
(this repo) also defaults to port `9990`
(`server.port: "${port:9990}"` in `src/main/resources/application.yaml`).
Running both with default settings on the same host would collide — one of
the two needs an explicit `-Dserver.port=...` override (or `PORT`/`port`
env var, per each app's own config) before both can run at once. Flagging
this now since it's exactly the kind of thing that's easy to hit once you
start standing up a second app here — not fixing anything yet, since this
doc is scoped to documenting gulliver as it exists today.

## Other things ruled out during troubleshooting

The history shows checking for, and confirming the absence of, other web
servers that could conflict with nginx on ports 80/443:

```bash
service httpd status
service apache2 status
systemctl status apache2
apachectl status
```

None were installed/running — nginx is the only web server on this box.

---

## Open questions / things worth deciding later

Everything that was unconfirmed as of the first draft of this doc (nginx
site config content, `postgresql.conf` settings, `ufw` rules) is now
confirmed above. What's left is genuinely forward-looking, not missing
information:

1. **No systemd unit for gulliver** — it doesn't restart on its own after a
   reboot or crash; someone has to notice and re-run the `nohup` command by
   hand (Step 6). Worth a `systemd` unit (`Restart=always`) if that manual
   step becomes a problem — not done today, just noting the gap.
2. **No TLS** — `hwsrv-1301707.hostwindsdns.com` is served over plain HTTP
   only (confirmed in the nginx config above). Fine for internal/UAT-style
   use, worth revisiting if this needs to be reachable by anyone who'd
   reasonably expect HTTPS.
3. **Running a second app on this box** (see [Ports](#ports) and the
   directory-layout note in [§3](#3-directory-layout)) needs, at minimum: a
   port other than `9990` for the second app, a new
   `/etc/nginx/sites-available/<app>` file (following the one-file-per-app
   pattern, not the abandoned `multiple-apps` approach), and — if it's a
   second Postgres-backed app — either a second database/role in the same
   PostgreSQL 17 instance, or a decision to run its own. None of this is
   done yet; this doc is scoped to gulliver as it exists today.
