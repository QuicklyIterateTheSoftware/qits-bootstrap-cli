# The platform's PostgreSQL image. It adds almost nothing to the upstream image — the repository
# exists so a database rides the same push/release/deploy lifecycle as every other component.
#
# The base is pulled through qits-mirror's pull-through cache, never from docker.io directly. The
# `hub` namespace maps to docker.io, and a single-component name under it means `library/`.
#
# QITS_DOMAIN is the platform's public domain; the release recipes pass it with
# `--opt build-arg:QITS_DOMAIN`. It is declared global, above this first FROM, so the FROM line
# itself can read it.
ARG QITS_DOMAIN=wohlben.eu
FROM mirror.qits.${QITS_DOMAIN}/hub/library/postgres:18.4

# BARE-BOOT PLACEHOLDER, not a credential, and dead on the platform from the very first boot. The
# image needs a password to start at all, so `docker run` with nothing else set still works for a
# throwaway container. On the platform the bootstrap CLI starts this image with
# `-e POSTGRES_PASSWORD=<randomized>`, and the deployer's run-args carry the same override, so the
# real superuser password applies at the initdb of the first data directory. A docker `-e` beats a
# Dockerfile ENV, so `qits-poc` never reaches a platform data dir. See README.md.
ENV POSTGRES_PASSWORD=qits-poc

# Minimal tuning for the blob-heavy workload this database is being taken to: multi-GiB ingest
# writes a lot of WAL.
#   shared_buffers=512MB              modest bump from the 128MB default; the rest stays page cache
#   max_wal_size=1GB                  a checkpoint per GiB of WAL; 4GB held ~3.5 GB of WAL on a
#                                     150 GB host for nothing (measured 2026-08-21) — the
#                                     multi-GiB registry ingest is chunked and does not need it
#   wal_compression=lz4               near-free on CPU, large cut in WAL volume
#   checkpoint_completion_target=0.9  spread checkpoint I/O instead of stalling on a spike
#   max_connections=300               the default 100 is the ceiling the estate already sits at:
#                                     every service holds its own pool, and a start-first rollout
#                                     runs two pools for one service while the successor boots, so
#                                     its Flyway got "remaining connection slots are reserved" and
#                                     swarm rolled the deploy back (qits-edge twice and
#                                     qits-workspaces once, 2026-09-27; ticket qits-522). ~10 MB per
#                                     backend worst case, so 300 fits the 16 GB host with room.
# Nothing else is configured here, and there are no init scripts: roles and databases are created
# by qits-deployments' `resources:` provisioning, not by this image.
CMD ["postgres", \
     "-c", "shared_buffers=512MB", \
     "-c", "max_wal_size=1GB", \
     "-c", "wal_compression=lz4", \
     "-c", "checkpoint_completion_target=0.9", \
     "-c", "max_connections=300"]
