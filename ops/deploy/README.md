# Production deployment

The lean production stack for the Qualification Verification System: the API, PostgreSQL and
Redis, on the `prod` profile, with the API served directly on port 80. This is the configuration
the system was deployed with for the assignment demonstration.

## Prerequisites

- Docker and the Compose plugin on the host.
- The API image built and available locally as `qvs-api:local`, or pushed to a registry and
  named via `QVS_IMAGE`.

## Steps

1. **Provide the secrets.** Copy `.env.example` to `.env` in this directory and fill in every
   value. Nothing has a default in the application — a missing secret stops start-up with a
   clear message rather than falling back to a value the repository could contain.

   Generate a fresh set on the host:

   ```bash
   QVS_DB_PASSWORD=$(openssl rand -hex 24)
   QVS_FIELD_KEY=$(openssl rand -base64 32)        # must decode to exactly 32 bytes
   QVS_NATIONAL_ID_SALT=$(openssl rand -hex 24)
   QVS_IP_SALT=$(openssl rand -hex 24)
   QVS_JWT_SECRET=$(openssl rand -hex 32)
   ```

2. **Set the first administrator** (only needed on a genuinely empty register):

   ```bash
   QVS_BOOTSTRAP_ADMIN_EMAIL=admin@your-institution.ac.zw
   QVS_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 15)   # at least 12 characters
   ```

   This account is created once, only while no accounts exist, and the application ignores these
   variables afterwards. The password is stored only as a bcrypt hash and is never logged.

3. **Start the stack:**

   ```bash
   docker compose -f docker-compose.prod.yml up -d
   ```

   The API applies its Flyway migrations on start and is ready in roughly fifteen seconds.
   Readiness can be checked on the host at `http://127.0.0.1:9090/actuator/health/readiness`.

## What is exposed, and what is not

| Port | Bound to | Reason |
|------|----------|--------|
| 80   | all interfaces | the console and API, on one origin |
| 9090 | loopback | actuator health/metrics — not public |
| 5433 | loopback | PostgreSQL — never internet-facing |
| 6380 | loopback | Redis — never internet-facing |

## Resource notes

Every container has a hard memory limit (`api` 600 MB, `postgres` 256 MB, `redis` 64 MB). On a
small or shared host, add swap as a cushion before starting:

```bash
sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
```

## Notes and limitations

- **TLS is not terminated here.** For a real deployment, put a reverse proxy (Caddy or nginx)
  with a certificate in front of port 80 before any real holder data is entered.
- Application rollback is instant — redeploy the previous image digest. Schema rollback is
  forward-only: migrations are additive, never destructive.
