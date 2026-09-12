# Infrastructure as code (BONUS-03)

The QVS deployment expressed as Terraform: network, volumes, database, rate limiter,
application, Prometheus and Grafana.

## Why the Docker provider and not a cloud one

Terraform for AWS or Azure would look more impressive and could not be run by anybody marking
it — no account, no credentials, no way to tell working code from code that merely parses. What
is here can be planned and applied on the same machine as the rest of the system, so it is
checked rather than asserted.

The trade-off is that this describes a single-host deployment. Nothing here does load balancing,
managed backups or multi-zone anything, and the files do not pretend otherwise.

## Running it

```bash
# The application image is built, not pulled: there is no registry in this deployment.
docker compose build api

cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # then fill it in

terraform init
terraform plan       # read this before applying anything
terraform apply
```

`terraform output` then prints the console, probe, Prometheus and Grafana URLs. No secret is
output — a sensitive output is still written to state in plaintext and still printed by
`terraform output -json`, so the safe number to return is zero.

Stop with `terraform destroy`. Note that this removes the **vault volume**, and with it the
private half of every institution signing key: every credential signed with them becomes
unverifiable. The vault refuses to overwrite a key precisely so that losing one is loud, and
destroying the volume is the one way to lose them quietly.

## Checked, not assumed

```bash
docker run --rm -v "$PWD:/work" -w /work hashicorp/terraform:1.9 fmt -check -diff
docker run --rm -v "$PWD:/work" -w /work hashicorp/terraform:1.9 init -backend=false
docker run --rm -v "$PWD:/work" -w /work hashicorp/terraform:1.9 validate
```

Both pass. Terraform does not need installing — it runs from its own image, like the k6 load
stage in `perf/`.

## Two descriptions of one system

`docker-compose.yml` and this directory both describe the same deployment, and that duplication
is a real cost rather than an oversight. Compose exists so the demo is one command and needs
nothing installed. Terraform exists so the deployment is a reviewable, versioned artefact with
explicit state and a plan you can read before anything changes. Neither replaces the other.

What is not acceptable is silent drift — somebody bumping PostgreSQL in one file and not the
other, producing a deployment that works on their machine and fails in the pipeline.
`InfrastructureParityTest` runs in the ordinary build and compares the parts where a
disagreement changes what actually runs:

- every image and its version tag
- the four host ports a person connects to
- that no published port binds beyond `127.0.0.1`
- that the same four secrets reach the container, and that no secret variable has a default

It deliberately does **not** demand the two agree about everything. Compose publishes PostgreSQL
and Redis on the host so a developer can attach a client; the Terraform does not, because a
deployment has no reason to put a database on a host interface. That is a considered difference,
and a test that forbade it would push the two files towards being one file — which would defeat
the point of having both.
