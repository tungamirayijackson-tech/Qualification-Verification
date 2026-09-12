# Everything that differs between one deployment and another.
#
# No secret has a default. The application refuses to start without them and so does this: a
# default here would be a credential in the repository, which is the single most common way a
# project ends up shipping a key everybody knows.

variable "project_name" {
  description = "Prefix for every resource this stack creates, so two deployments can share a host."
  type        = string
  default     = "qvs"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.project_name))
    error_message = "project_name must be lowercase letters, digits and hyphens, 2-21 characters."
  }
}

variable "api_image" {
  description = "The application image. Build it with `docker compose build api` or from CI."
  type        = string
  default     = "qvs-api:local"
}

variable "postgres_image" {
  description = "PostgreSQL image. Pinned to a patch line, not to `latest`."
  type        = string
  default     = "postgres:16-alpine"
}

variable "redis_image" {
  description = "Redis image, for the shared rate-limit budget (FR-11)."
  type        = string
  default     = "redis:7-alpine"
}

variable "prometheus_image" {
  description = "Prometheus image."
  type        = string
  default     = "prom/prometheus:v2.54.1"
}

variable "grafana_image" {
  description = "Grafana image."
  type        = string
  default     = "grafana/grafana:11.2.0"
}

variable "profile" {
  description = "Spring profile. `dev` seeds demo data and accounts with a published password."
  type        = string
  default     = "prod"

  validation {
    # Named explicitly rather than left free, because `dev` creates accounts whose password is
    # written in the repository. Reaching it should take a deliberate act.
    condition     = contains(["dev", "prod"], var.profile)
    error_message = "profile must be dev or prod."
  }
}

variable "http_port" {
  description = "Host port for the application. Bound to loopback."
  type        = number
  default     = 8081
}

variable "management_port" {
  description = <<-EOT
    Host port for the actuator. Separate from the application port so Prometheus can scrape
    metrics without a bearer token -- this system issues short-lived tokens to people, not
    long-lived ones to scrapers, and metrics disclose request rates, error counts and the
    shape of the deployment.
  EOT
  type        = number
  default     = 9090
}

variable "field_key" {
  description = "Base64 32-byte AES key protecting holder names and private key material."
  type        = string
  sensitive   = true
}

variable "national_id_salt" {
  description = "Salt for the national-ID hash, so it cannot be attacked with a precomputed table."
  type        = string
  sensitive   = true
}

variable "ip_salt" {
  description = "Salt for client-address hashes in the verification log."
  type        = string
  sensitive   = true
}

variable "jwt_secret" {
  description = "HMAC key for access tokens. At least 32 bytes, or HS256 is HS256 in name only."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.jwt_secret) >= 32
    error_message = "jwt_secret must be at least 32 characters."
  }
}

variable "database_password" {
  description = "PostgreSQL password for the application's own role."
  type        = string
  sensitive   = true
}

variable "grafana_password" {
  description = "Grafana admin password. Set rather than defaulted, so the demo is never admin/admin."
  type        = string
  sensitive   = true
}
