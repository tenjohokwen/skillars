# External Uptime Monitor Setup

This guide configures an external uptime monitor that alerts within 5 minutes when
`/actuator/health` becomes unreachable from outside the Node. The monitor runs **independently of
the Node** — if the entire server (including the LGTM stack) goes offline, this is the only alert
path that remains active.

**Recommended service:** [UptimeRobot](https://uptimerobot.com) free tier — 50 monitors, 5-minute
check intervals, email and webhook (Slack) notifications. No credit card required.

---

## Step 1: Create a UptimeRobot Account

1. Go to [https://uptimerobot.com](https://uptimerobot.com) and sign up for a free account
2. Verify your email address

---

## Step 2: Add a New Monitor

1. In the UptimeRobot dashboard, click **Add New Monitor**
2. Fill in the settings:

   | Setting | Value |
   |---|---|
   | Monitor Type | **HTTP(S)** |
   | Friendly Name | `Skillars Production` |
   | URL | `https://<YOUR_DOMAIN>/actuator/health` |
   | Monitoring Interval | **5 minutes** |

3. Enable **Keyword Monitoring**:
   - Keyword: `"status":"UP"`
   - Alert when: **keyword not found**
   - This catches cases where the endpoint returns HTTP 200 but the application is DOWN (the Spring
     Boot health endpoint returns `{"status":"DOWN"}` with HTTP 503 when unhealthy, but keyword
     monitoring also catches any unexpected 200 body that lacks the UP status)
   - **Before saving:** verify the exact response body by visiting
     `https://<YOUR_DOMAIN>/actuator/health` in a browser or running
     `curl -s https://<YOUR_DOMAIN>/actuator/health`. Copy the keyword string
     directly from the response — do not retype it, as smart quotes or extra spaces will cause a
     permanent match failure that silently disables alerting

4. Click **Create Monitor**

---

## Step 3: Configure Email Alert Contact

1. In UptimeRobot, go to **My Settings** → **Alert Contacts** → **Add Alert Contact**
2. Set:
   - Alert Contact Type: **E-mail**
   - Friendly Name: `Skillars Ops Email`
   - E-mail: your operations email address
3. Click **Save**
4. Verify the contact by clicking the confirmation link sent to that address

---

## Step 4: Configure Slack Alert Contact

1. Create a Slack Incoming Webhook:
   - You can reuse the existing webhook configured for `SLACK_WEBHOOK_URL` in GitHub Actions, or
     create a dedicated one
   - Slack → Your workspace → Apps → Incoming Webhooks → Add to Slack → select `#alerts` channel
     → copy the Webhook URL
2. In UptimeRobot, go to **My Settings** → **Alert Contacts** → **Add Alert Contact**
3. Set:
   - Alert Contact Type: **Slack**
   - Friendly Name: `Skillars Ops Slack`
   - Webhook URL: paste the Slack webhook URL
4. Click **Save**

---

## Step 5: Assign Alert Contacts to the Monitor

1. Open the **Skillars Production** monitor in UptimeRobot
2. Under **Alert Contacts to Notify**, add both:
   - `Skillars Ops Email`
   - `Skillars Ops Slack`
3. Set **Alert Threshold** to **1 failure** (alert immediately on first missed check)
4. Save the monitor

---

## Step 6: Verify the Monitor

Once the monitor shows status **UP** in the dashboard:

1. Check the **Status Dashboard** in UptimeRobot — the monitor should show green with the last
   check timestamp
2. Optionally, test alerting by temporarily pointing the URL to an invalid path and confirming you
   receive notifications (remember to revert)

---

## Timing Expectations

The 5-minute check interval means:

- **Best case:** Alert fires ~5 minutes after the outage begins (first check after the Node goes
  down)
- **Worst case:** Alert fires ~10 minutes after the outage begins (outage occurs immediately after a
  successful check, so the next check is 5 minutes later, plus alert dispatch time)

This satisfies the requirement of alerting within 5 minutes for **sustained outages** — a single
transient failure (network blip) does not trigger an alert if the next check passes.

---

## Why External Monitoring Matters

The internal LGTM stack (Grafana, Prometheus, Loki) runs as Docker containers on the same Node as
the application. If the Node crashes, runs out of memory, or loses network connectivity, both the
application **and** the internal alerting infrastructure go offline simultaneously. UptimeRobot
checks from external infrastructure (Ixnay data centers worldwide) and remains operational
regardless of the Node's state.
