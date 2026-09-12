# What an operator needs after `terraform apply`, and nothing they should not have.
#
# No secret is output, not even marked sensitive. A sensitive output is still written to the
# state file in plaintext and still printed by `terraform output -json`; the safe number of
# secrets to return from here is zero.

output "console_url" {
  description = "The Angular console and the public verification page."
  value       = "http://127.0.0.1:${var.http_port}"
}

output "liveness_url" {
  description = "Liveness probe, on the management port."
  value       = "http://127.0.0.1:${var.management_port}/actuator/health/liveness"
}

output "readiness_url" {
  description = "Readiness probe, on the management port."
  value       = "http://127.0.0.1:${var.management_port}/actuator/health/readiness"
}

output "prometheus_url" {
  description = "Prometheus, including its alerts view."
  value       = "http://127.0.0.1:9091"
}

output "grafana_url" {
  description = "Grafana. The password is the one supplied as a variable; it is not echoed here."
  value       = "http://127.0.0.1:3001"
}

output "network_name" {
  description = "The container network, for attaching a k6 load stage (see perf/README.md)."
  value       = docker_network.qvs.name
}
