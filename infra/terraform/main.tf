# The QVS deployment, as code.
#
# The same topology docker-compose.yml describes, expressed with explicit state and explicit
# dependencies. Compose exists so the demo is one command; this exists so the deployment is a
# reviewable, versioned artefact with a plan you can read before anything changes.
#
# That duplication is real and worth naming rather than hiding: two files describe one system
# and they can drift. `ops/check-parity.py` compares the images and ports in both and fails when
# they disagree, which is the only part of the drift that silently produces a wrong deployment.

locals {
  name = var.project_name

  # Where the repository sits, so provisioning files can be mounted read-only from it rather
  # than baked into images. Dashboards and scrape configs belong in version control.
  repo_root = abspath("${path.module}/../..")

  database_name = "qvs"
  database_user = "qvs"
}

# ------------------------------------------------------------------------ network

resource "docker_network" "qvs" {
  name = "${local.name}-net"
}

# ------------------------------------------------------------------------ volumes

resource "docker_volume" "pgdata" {
  name = "${local.name}-pgdata"
}

# Private signing keys. Losing this volume means losing the private half of every institution
# key, and every credential signed with them becomes unverifiable. The vault refuses to
# overwrite a key precisely so that this is loud rather than silent.
resource "docker_volume" "vault" {
  name = "${local.name}-vault"
}

resource "docker_volume" "promdata" {
  name = "${local.name}-promdata"
}

resource "docker_volume" "grafanadata" {
  name = "${local.name}-grafanadata"
}

# ------------------------------------------------------------------------- images

data "docker_registry_image" "postgres" {
  name = var.postgres_image
}

resource "docker_image" "postgres" {
  name          = data.docker_registry_image.postgres.name
  pull_triggers = [data.docker_registry_image.postgres.sha256_digest]
}

data "docker_registry_image" "redis" {
  name = var.redis_image
}

resource "docker_image" "redis" {
  name          = data.docker_registry_image.redis.name
  pull_triggers = [data.docker_registry_image.redis.sha256_digest]
}

data "docker_registry_image" "prometheus" {
  name = var.prometheus_image
}

resource "docker_image" "prometheus" {
  name          = data.docker_registry_image.prometheus.name
  pull_triggers = [data.docker_registry_image.prometheus.sha256_digest]
}

data "docker_registry_image" "grafana" {
  name = var.grafana_image
}

resource "docker_image" "grafana" {
  name          = data.docker_registry_image.grafana.name
  pull_triggers = [data.docker_registry_image.grafana.sha256_digest]
}

# The application image is built locally, not pulled: there is no registry in this deployment,
# and a `docker_registry_image` lookup for it would fail before anything else could run.
resource "docker_image" "api" {
  name         = var.api_image
  keep_locally = true
}

# ----------------------------------------------------------------------- database

resource "docker_container" "postgres" {
  name    = "${local.name}-postgres"
  image   = docker_image.postgres.image_id
  restart = "unless-stopped"

  env = [
    "POSTGRES_DB=${local.database_name}",
    "POSTGRES_USER=${local.database_user}",
    "POSTGRES_PASSWORD=${var.database_password}",
  ]

  networks_advanced {
    name = docker_network.qvs.name
  }

  volumes {
    volume_name    = docker_volume.pgdata.name
    container_path = "/var/lib/postgresql/data"
  }

  # Not published. The database is reachable from the application over the container network
  # and from nowhere else; a mapped port here would be a database on the host's interfaces.
  healthcheck {
    test     = ["CMD-SHELL", "pg_isready -U ${local.database_user} -d ${local.database_name}"]
    interval = "5s"
    timeout  = "3s"
    retries  = 20
  }
}

# ----------------------------------------------------------------------- rate limit

resource "docker_container" "redis" {
  name    = "${local.name}-redis"
  image   = docker_image.redis.image_id
  restart = "unless-stopped"

  networks_advanced {
    name = docker_network.qvs.name
  }

  healthcheck {
    test     = ["CMD", "redis-cli", "ping"]
    interval = "5s"
    timeout  = "3s"
    retries  = 20
  }
}

# ---------------------------------------------------------------------- application

resource "docker_container" "api" {
  name    = "${local.name}-api"
  image   = docker_image.api.image_id
  restart = "unless-stopped"

  # Terraform starts containers concurrently unless told otherwise. Flyway against a
  # half-started PostgreSQL fails in a way that reads like a schema problem.
  depends_on = [docker_container.postgres, docker_container.redis]

  env = [
    "SPRING_PROFILES_ACTIVE=${var.profile}",
    "QVS_DB_URL=jdbc:postgresql://${docker_container.postgres.name}:5432/${local.database_name}",
    "QVS_DB_USER=${local.database_user}",
    "QVS_DB_PASSWORD=${var.database_password}",
    "QVS_REDIS_HOST=${docker_container.redis.name}",
    "QVS_REDIS_PORT=6379",
    "QVS_VAULT_DIR=/vault",
    "MANAGEMENT_SERVER_PORT=${var.management_port}",
    "QVS_FIELD_KEY=${var.field_key}",
    "QVS_NATIONAL_ID_SALT=${var.national_id_salt}",
    "QVS_IP_SALT=${var.ip_salt}",
    "QVS_JWT_SECRET=${var.jwt_secret}",
  ]

  networks_advanced {
    name = docker_network.qvs.name
    # A stable alias, so Prometheus's scrape config can name it without knowing the prefix.
    aliases = ["api"]
  }

  volumes {
    volume_name    = docker_volume.vault.name
    container_path = "/vault"
  }

  ports {
    internal = 8080
    external = var.http_port
    ip       = "127.0.0.1"
  }

  ports {
    internal = var.management_port
    external = var.management_port
    ip       = "127.0.0.1"
  }
}

# ----------------------------------------------------------------------- monitoring

resource "docker_container" "prometheus" {
  name    = "${local.name}-prometheus"
  image   = docker_image.prometheus.image_id
  restart = "unless-stopped"

  depends_on = [docker_container.api]

  command = [
    "--config.file=/etc/prometheus/prometheus.yml",
    "--storage.tsdb.retention.time=7d",
  ]

  networks_advanced {
    name = docker_network.qvs.name
  }

  # Mounted read-only from the repository. A scrape config or an alert rule edited inside a
  # running container is a change nobody reviewed and nobody can find again.
  volumes {
    host_path      = "${local.repo_root}/ops/prometheus/prometheus.yml"
    container_path = "/etc/prometheus/prometheus.yml"
    read_only      = true
  }

  volumes {
    host_path      = "${local.repo_root}/ops/prometheus/alerts.yml"
    container_path = "/etc/prometheus/alerts.yml"
    read_only      = true
  }

  volumes {
    volume_name    = docker_volume.promdata.name
    container_path = "/prometheus"
  }

  ports {
    internal = 9090
    external = 9091
    ip       = "127.0.0.1"
  }
}

resource "docker_container" "grafana" {
  name    = "${local.name}-grafana"
  image   = docker_image.grafana.image_id
  restart = "unless-stopped"

  depends_on = [docker_container.prometheus]

  env = [
    "GF_SECURITY_ADMIN_USER=qvs",
    "GF_SECURITY_ADMIN_PASSWORD=${var.grafana_password}",
    "GF_USERS_ALLOW_SIGN_UP=false",
    "GF_AUTH_ANONYMOUS_ENABLED=false",
    "GF_ANALYTICS_REPORTING_ENABLED=false",
    "GF_ANALYTICS_CHECK_FOR_UPDATES=false",
  ]

  networks_advanced {
    name = docker_network.qvs.name
  }

  volumes {
    host_path      = "${local.repo_root}/ops/grafana/provisioning"
    container_path = "/etc/grafana/provisioning"
    read_only      = true
  }

  volumes {
    host_path      = "${local.repo_root}/ops/grafana/dashboards"
    container_path = "/etc/grafana/dashboards"
    read_only      = true
  }

  volumes {
    volume_name    = docker_volume.grafanadata.name
    container_path = "/var/lib/grafana"
  }

  ports {
    internal = 3000
    external = 3001
    ip       = "127.0.0.1"
  }
}
