# The workspace EDITOR image: the released workspace image with openvscode-server laid on top.
#
# It is the workspace container image plus one directory. Nothing else. No `USER`, no `WORKDIR`, no
# `EXPOSE`, no `ENTRYPOINT`, no `CMD` — the base already sets `WORKDIR /workspace`, runs as root so
# an arbitrary runtime uid can work in the checkout, and entrypoints to the daemon that IS the
# container's PID 1. Adding any of those here would take a workspace away from the daemon and give
# it to an editor, which is backwards: the editor is a process the daemon starts, not the container.
#
# WHY IT IS A SEPARATE IMAGE AND NOT A LAYER ON THE WORKSPACE ITSELF. openvscode-server unpacks to a
# few hundred megabytes that only a workspace with the editor turned on ever reads. Every ordinary
# workspace would carry them, pull them and page them in for nothing. Putting the binary in a child
# image keeps the plain workspace exactly as heavy as it is today, and makes "does this container
# have an editor" a question about which image it was started from rather than about a runtime flag.
#
# WHAT IS DELIBERATELY NOT HERE:
#   - No launch flags, no connection token, no `--server-base-path`. How the editor is started is
#     the daemon's business and it changes without rebuilding this image. This file only puts the
#     binary somewhere the daemon can find it, at a path the daemon can hard-code.
#   - No extension gallery configuration. Open VSX is not reachable from the platform network, so
#     the gallery stays whatever the distribution ships and the editor runs on its bundled
#     extensions alone. If that ever needs to be turned off explicitly it belongs to the launch, not
#     to the recipe.
#   - No `/opt/openvscode-server` ownership or permission fiddling. The tarball extracts world
#     readable and the server writes only under `$HOME`, which the base already makes writable for
#     the arbitrary uid.
#
# --- the base pin ---------------------------------------------------------------------------------
# ONE LINE, ONE VERSION TOKEN, AND A MACHINE EDITS IT. This line is a *pin* in qits-maintenance's
# inventory — it reads literal `ARG <NAME>=<image>:<tag>` defaults as docker pins, at `arg:
# WORKSPACE_IMAGE`, and its bump pipeline rewrites the tag in place. The registry host is dropped
# when the image is matched, so `registry.qits.${QITS_DOMAIN}/qits/workspace` is the same pin as the
# internal `qits/workspace` whose releases move it. Keep the value LITERAL: a `$`, a `@`, a `://` or
# a tag with no `/` before it are each enough to make maintenance stop seeing this as a pin, and the
# base would then silently stop following the toolchain.
#
# PINNED BY VERSION, NEVER FOLLOWED BY A FLOATING TAG. qits-workspace-daemon publishes `:<version>`
# and `:<sha>` and no floating tag at all (its own ONE TAG PER PIPELINE rule), so there is nothing to
# follow even if we wanted to — and following one would make this image unreproducible: rebuilding a
# released tag would silently pick up a different workspace underneath the same editor.
#
# THE DEFAULT IS THE PIN, WHICH IS WHY OVERRIDING IT IS SAFE. CI passes no `--build-arg`, so a
# release rebuilds on exactly the workspace image its tree was released with. A developer with a
# hand-built workspace passes one:
#
#     docker build -t qits/workspace-editor:local . \
#       --build-arg WORKSPACE_IMAGE=qits/workspace:native
#
# ARG BEFORE FROM, WHICH IS THE ONLY PLACE IT WORKS. An `ARG` declared after a `FROM` belongs to that
# stage and cannot be read by the `FROM` line itself. This one is global, hence its re-declaration
# below to bring it back into the stage for the provenance file.
#
# The platform's public domain (qits-731). Global, and declared above WORKSPACE_IMAGE, so that
# default can read it; the release recipes pass `--opt build-arg:QITS_DOMAIN`.
ARG QITS_DOMAIN=wohlben.eu
ARG WORKSPACE_IMAGE=registry.qits.${QITS_DOMAIN}/qits/workspace:2026.1105.104934
FROM ${WORKSPACE_IMAGE}

# --- openvscode-server ----------------------------------------------------------------------------
# The upstream distribution, unpacked under /opt — the same shape as the jdtls language server in
# qits-workspace-oci's Dockerfile: pinned by ARG so a build can repin without editing the recipe,
# fetched with `curl -fsSL` (`-f` is what turns an HTML error page into a failed build), unpacked,
# and then ASSERTED. The `test -x` is the whole reason the assertion is here: a structure change
# upstream would otherwise ship an image whose editor is missing, and the first thing to notice
# would be a user clicking Editor on a live workspace.
#
# `--strip-components=1` because the tarball carries one top-level
# `openvscode-server-v<version>-linux-x64/` directory and the daemon is going to spell
# `/opt/openvscode-server/bin/openvscode-server`, not a path with a version in it. That constant
# path is the contract between this image and its launcher; the version lives in the ARG and in
# /etc/qits-editor-provenance, nowhere else.
#
# THE CHECKSUM IS PART OF THE PIN, unlike jdtls — and the difference is that jdtls is fetched from a
# `-latest` snapshot URL where no checksum can exist, while this URL names an immutable release
# asset. A GitHub release asset can be deleted and re-uploaded under the same name; this line is what
# makes that a red build instead of a silent substitution. BUMPING `OPENVSCODE_VERSION` MEANS BUMPING
# `OPENVSCODE_SHA256` IN THE SAME COMMIT — the build fails loudly if you forget, which is the point.
#
# linux-x64 is hard-coded because the platform builds and runs amd64 only. An arm64 build would need
# the `-linux-arm64` asset and its own sum; `TARGETARCH` is not used here because a per-arch checksum
# table would be a second thing to keep matched for an architecture nobody builds.
ARG OPENVSCODE_VERSION=1.109.5
ARG OPENVSCODE_SHA256=b433bf4f0227321a7014d8460d10a8f958adc0f45aa79bd889e84e65e8f88363
ARG OPENVSCODE_URL=https://github.com/gitpod-io/openvscode-server/releases/download/openvscode-server-v${OPENVSCODE_VERSION}/openvscode-server-v${OPENVSCODE_VERSION}-linux-x64.tar.gz

# Re-declared so the provenance file can record which workspace image this editor was laid on. The
# global ARG above is out of scope inside the stage; naming it again with no default inherits the
# value the build resolved.
ARG WORKSPACE_IMAGE

# One RUN, because the tarball must not survive into a layer: at ~77 MB compressed it would double
# the cost of the only thing this image adds. `sha256sum -c` reads the sum from stdin so the expected
# value never becomes a filename.
#
# /etc/qits-editor-provenance IS WRITTEN HERE AND NOT IN A SECOND RUN so that it cannot describe a
# fetch that did not happen. It is key=value on purpose: a support question about a live workspace is
# answered by `docker exec … cat /etc/qits-editor-provenance` without a registry lookup, and the file
# names the base image too — so one read says both which editor and which workspace are running.
RUN mkdir -p /opt/openvscode-server \
    && curl -fsSL "${OPENVSCODE_URL}" -o /tmp/openvscode-server.tar.gz \
    && printf '%s  %s\n' "${OPENVSCODE_SHA256}" /tmp/openvscode-server.tar.gz | sha256sum -c - \
    # `--no-same-owner`: nothing here asserts the tarball's entries are owned by an id this image
    # can afford to keep. A CI runner whose docker lives in a user namespace fails to even mount an
    # image carrying a file above its mapped range ("failed to Lchown ... subordinate IDs" —
    # qits-556); the same fix already went into qits-workspace-oci's jdtls extraction, which this
    # base image itself now carries clean, so this is the only other tar extraction left to cover.
    && tar -xz --no-same-owner -C /opt/openvscode-server --strip-components=1 -f /tmp/openvscode-server.tar.gz \
    && rm -f /tmp/openvscode-server.tar.gz \
    && test -x /opt/openvscode-server/bin/openvscode-server \
    && printf 'openvscode-version=%s\nopenvscode-url=%s\nopenvscode-sha256=%s\nopenvscode-home=%s\nworkspace-image=%s\n' \
        "${OPENVSCODE_VERSION}" \
        "${OPENVSCODE_URL}" \
        "${OPENVSCODE_SHA256}" \
        /opt/openvscode-server \
        "${WORKSPACE_IMAGE}" \
        > /etc/qits-editor-provenance \
    && chmod 0644 /etc/qits-editor-provenance

# Guard of last resort, last RUN in the image so it sees the openvscode-server layer above plus
# everything the workspace base underneath it already carries: fail the build if any path landed
# owned by a uid or gid above 65535. The extraction above is fixed with `--no-same-owner`, but this
# also re-checks the inherited base in case a future workspace-base release regresses — a CI runner
# whose docker lives in a user namespace cannot map such an id and fails to even mount the image
# (qits-556), so catching it here fails this build's own gate rather than every downstream one.
# `-xdev` stays within this image's single filesystem (no bind mounts at build time), so it never
# reaches /proc or /sys, which are not part of the image anyway.
RUN bad="$(find / -xdev \( -uid +65535 -o -gid +65535 \) -print 2>/dev/null | head -20)"; \
    if [ -n "$bad" ]; then \
        echo "Files owned by an id above 65535 (a CI runner in a user namespace cannot map them - qits-556):" >&2; \
        echo "$bad" >&2; \
        exit 1; \
    fi
