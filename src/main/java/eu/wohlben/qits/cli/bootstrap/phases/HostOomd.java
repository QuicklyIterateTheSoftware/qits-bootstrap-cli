package eu.wohlben.qits.cli.bootstrap.phases;

import java.util.List;

/**
 * <b>The host's answer to memory pressure, as one idempotent shell script.</b>
 * <p>
 * A host with no early OOM killer and no swap does not lose a workload under pressure — it
 * LIVELOCKS. On 2026-09-11 wohlben.eu (16 GB) answered ping and nothing else: ssh and the provider
 * console both timed out and the machine needed a hard reset, and the kernel's own OOM killer never
 * fired, because with swap the host crawled to a halt long before any allocation failed.
 * {@code oom_score_adj} only orders the kernel's victims once it acts, so it did not help and could
 * not. A failed build or a killed agent is always better than a host restart.
 * <p>
 * {@code systemd-oomd} is what acts first: it reads pressure stall information per cgroup and kills
 * a LEAF cgroup before the host stops answering. Docker uses the systemd cgroup driver here, so
 * every container is {@code system.slice/docker-<id>.scope} and every build is a sub-cgroup of
 * buildkitd's — which is why the pressure rule on {@code system.slice} kills the build that grew
 * rather than the daemon that started it.
 * <p>
 * <b>Four facts this script writes, and each one is the state a person set by hand on the node that
 * livelocked:</b>
 * <ol>
 *   <li>the package, where the unit is not installed and the host has {@code apt-get};
 *   <li>{@code ManagedOOMMemoryPressure=kill} at {@value #PRESSURE_LIMIT} on {@code system.slice} —
 *       oomd's own {@code DefaultMemoryPressureDurationSec=20s} is the dwell;
 *   <li>{@code ManagedOOMSwap=kill} on the root slice, which fires when RAM and swap are both over
 *       90% and kills the cgroup using the most swap;
 *   <li>{@code ManagedOOMPreference=omit} on the units that must survive the cure. <b>containerd is
 *       the load-bearing one</b>: every container's shim lives in its cgroup, so killing it stops
 *       every container on the host at once.
 * </ol>
 * And swap itself, because oomd's own documentation asks for it: without swap a host under
 * pressure livelocks faster than oomd reacts.
 * <p>
 * <b>Everything here is written only when it differs, and that is what keeps a rerun quiet.</b> The
 * service is restarted only when a drop-in actually changed; an existing swap — of any kind, at any
 * size — is left exactly as it is, and so is an existing {@code /swapfile} that is not on. The one
 * thing this never does is take space it does not have: a swapfile is created only where the root
 * filesystem has the file's size and {@value #SWAP_HEADROOM_GB} GB more to spare.
 * <p>
 * <b>Why a script and not a phase full of process calls:</b> it runs in the HOST's namespaces
 * through one throwaway privileged helper, the same seam {@link SeedPhases#ipv6Loopback} uses. One
 * process crossing that seam is one thing to read in the run log, and the parts that are worth
 * testing — what is written, what is asked afterwards — are strings this class builds.
 */
final class HostOomd {

    private HostOomd() {
    }

    /** What every line this script means to be read by carries, so the noise sorts itself out. */
    static final String MARKER = "qits-oom:";

    /**
     * Pressure above this for oomd's default 20 seconds is what counts as the host going under. It
     * is the value measured on the node: a 100 MB-capped memory hog in a test slice was killed
     * within 12 seconds, and nineteen platform services stayed up around it.
     */
    static final String PRESSURE_LIMIT = "50%";

    /**
     * The swapfile, in gigabytes — a quarter of the node's RAM, and the size the hand setup used
     * on 2026-08-22.
     */
    static final int SWAP_GB = 8;

    /** Room left on the root filesystem AFTER the swapfile, or no swapfile is made. */
    static final int SWAP_HEADROOM_GB = 8;

    /**
     * <b>The units that must never be oomd's victim.</b> containerd holds every container's shim,
     * docker holds the daemon that answers this run, ssh is how a person gets back in when it goes
     * wrong at all, and journald is what says what happened. {@code ManagedOOMPreference} is the
     * only priority knob oomd has — it ignores {@code oom_score_adj} entirely — and it cannot be set
     * durably on a docker scope, because those come and go with the containers.
     */
    static final List<String> OMIT_UNITS =
            List.of("docker.service", "containerd.service", "ssh.service", "systemd-journald.service");

    /** Where a drop-in of this program's goes, whatever it is a drop-in of. */
    static final String DROP_IN = "50-qits-oomd.conf";

    /**
     * The whole act: install, write, reload, swap, and then ASK the host what it now says. The
     * verdict line is the last thing printed and the only thing the phase decides on — a script
     * that reported what it wrote rather than what the host answers would call a failed restart
     * a success.
     */
    static String script() {
        return """
                set -u
                say() { echo "%MARKER% $*"; }

                # A host with no systemd has none of this to configure, and that is not a failure:
                # the platform runs, it just has no early killer. Said once and left alone.
                if ! command -v systemctl >/dev/null 2>&1; then
                  say "systemd=absent"
                  exit 0
                fi

                changed=0
                write() {
                  path=$1
                  mkdir -p "$(dirname "$path")"
                  cat > "$path.qits-new"
                  if [ -f "$path" ] && cmp -s "$path.qits-new" "$path"; then
                    rm -f "$path.qits-new"
                  else
                    mv "$path.qits-new" "$path"
                    changed=1
                    say "wrote $path"
                  fi
                }

                # The package ships the unit already enabled, and it carries
                # DefaultMemoryPressureDurationSec=20s — the dwell the limit below is measured
                # against. Only apt-get is known here; anything else is reported for a person.
                if ! systemctl cat systemd-oomd.service >/dev/null 2>&1; then
                  if command -v apt-get >/dev/null 2>&1; then
                    say "installing systemd-oomd"
                    DEBIAN_FRONTEND=noninteractive apt-get install -y systemd-oomd >/dev/null 2>&1 \\
                      || { apt-get update >/dev/null 2>&1 \\
                           && DEBIAN_FRONTEND=noninteractive apt-get install -y systemd-oomd \\
                              >/dev/null 2>&1; } \\
                      || say "the systemd-oomd package would not install"
                  else
                    say "systemd-oomd is not installed and this host has no apt-get"
                  fi
                fi

                write /etc/systemd/system/system.slice.d/%DROP_IN% <<'EOF'
                # Written by the qits bootstrap. Memory pressure in system.slice above the limit
                # for oomd's dwell kills the leaf cgroup reclaiming hardest — the build or the
                # agent that grew, never the daemon that started it.
                [Slice]
                ManagedOOMMemoryPressure=kill
                ManagedOOMMemoryPressureLimit=%PRESSURE_LIMIT%
                EOF

                write /etc/systemd/system/-.slice.d/%DROP_IN% <<'EOF'
                # Written by the qits bootstrap. RAM and swap both over 90% kills whatever is
                # using the most swap.
                [Slice]
                ManagedOOMSwap=kill
                EOF

                for unit in %OMIT_UNITS%; do
                  systemctl cat "$unit" >/dev/null 2>&1 || continue
                  write "/etc/systemd/system/$unit.d/%DROP_IN%" <<'EOF'
                # Written by the qits bootstrap. This unit is never oomd's victim. containerd is
                # why the list exists: every container's shim lives in its cgroup, so killing it
                # stops every container on the host at once.
                [Service]
                ManagedOOMPreference=omit
                EOF
                done

                # A reload is what puts user.oomd_omit on the RUNNING units' cgroups, so neither
                # docker nor containerd is restarted for any of this.
                if [ "$changed" = 1 ]; then
                  systemctl daemon-reload || say "daemon-reload failed"
                fi
                if systemctl cat systemd-oomd.service >/dev/null 2>&1; then
                  systemctl enable --now systemd-oomd >/dev/null 2>&1 \\
                    || say "systemd-oomd would not start"
                  if [ "$changed" = 1 ]; then
                    systemctl restart systemd-oomd >/dev/null 2>&1 \\
                      || say "systemd-oomd would not restart"
                  fi
                fi

                # ANY swap is enough swap: what is already on was somebody's decision about this
                # machine, and oomd only needs the kernel to have somewhere to go before it acts.
                if [ -n "$(swapon --show=NAME --noheadings 2>/dev/null)" ]; then
                  say "swap already on"
                elif [ -e /swapfile ]; then
                  # Off but present is a half-made or deliberately disabled one. Overwriting it
                  # would destroy whatever it holds for a gain nobody asked for.
                  say "/swapfile exists and is not on — left alone"
                else
                  avail=$(df -Pk / | awk 'NR==2 {print $4}')
                  need=$(( (%SWAP_GB% + %SWAP_HEADROOM_GB%) * 1024 * 1024 ))
                  if [ "${avail:-0}" -lt "$need" ]; then
                    say "no swap, and / has ${avail:-0}k free — a %SWAP_GB%G swapfile needs ${need}k"
                  else
                    { fallocate -l %SWAP_GB%G /swapfile 2>/dev/null \\
                        || dd if=/dev/zero of=/swapfile bs=1M count=$(( %SWAP_GB% * 1024 )) \\
                           status=none; } \\
                      && chmod 600 /swapfile \\
                      && mkswap /swapfile >/dev/null 2>&1 \\
                      && swapon /swapfile \\
                      && say "made and turned on a %SWAP_GB%G /swapfile" \\
                      || say "the %SWAP_GB%G /swapfile could not be made"
                    grep -qs '^/swapfile ' /etc/fstab \\
                      || echo '/swapfile none swap sw 0 0' >> /etc/fstab
                  fi
                fi

                # Appended rather than written: 99-qits.conf is the host's file and may hold
                # settings this program knows nothing about. A swappiness already spelled there is
                # a person's answer and stands.
                if ! grep -qs '^vm.swappiness' /etc/sysctl.d/99-qits.conf; then
                  mkdir -p /etc/sysctl.d
                  echo 'vm.swappiness=20' >> /etc/sysctl.d/99-qits.conf
                  sysctl -w vm.swappiness=20 >/dev/null 2>&1 || say "vm.swappiness would not apply"
                  say "wrote vm.swappiness=20 to /etc/sysctl.d/99-qits.conf"
                fi

                # THE VERDICT IS THE HOST'S OWN ANSWER, not a summary of what was attempted.
                say "state" \\
                  "oomd=$(systemctl is-active systemd-oomd 2>/dev/null || echo unknown)" \\
                  "pressure=$(systemctl show system.slice -p ManagedOOMMemoryPressure --value \\
                     2>/dev/null)" \\
                  "limit=$(systemctl show system.slice -p ManagedOOMMemoryPressureLimit --value \\
                     2>/dev/null)" \\
                  "swap-kill=$(systemctl show -- -.slice -p ManagedOOMSwap --value 2>/dev/null)" \\
                  "swap=$(swapon --show=NAME --noheadings 2>/dev/null | tr '\\n' ' ' | tr -s ' ' \\
                     | sed 's/ $//')"
                """
                .replace("%MARKER%", MARKER)
                .replace("%DROP_IN%", DROP_IN)
                .replace("%PRESSURE_LIMIT%", PRESSURE_LIMIT)
                .replace("%OMIT_UNITS%", String.join(" ", OMIT_UNITS))
                .replace("%SWAP_GB%", String.valueOf(SWAP_GB))
                .replace("%SWAP_HEADROOM_GB%", String.valueOf(SWAP_HEADROOM_GB));
    }

    /**
     * What the host answered, read back off the script's own verdict line.
     *
     * @param oomd        {@code active} when the service is running
     * @param pressure    the pressure action on {@code system.slice} — {@code kill} when it is set
     * @param limit       the pressure limit that action fires at, for the log
     * @param swapKill    the swap action on the root slice — {@code kill} when it is set
     * @param swap        what is swapped on, empty when nothing is
     * @param systemdless a host with no systemd, where none of this applies
     */
    record State(String oomd, String pressure, String limit, String swapKill, String swap,
                 boolean systemdless) {

        /** Everything the ticket asked for, as the host itself now reports it. */
        boolean complete() {
            return "active".equals(oomd) && "kill".equals(pressure) && "kill".equals(swapKill)
                    && !swap.isBlank();
        }

        /** The one line the run's header carries, and the first half of any warning. */
        String describe() {
            if (systemdless) {
                return "no systemd — nothing to configure";
            }
            return "oomd " + oomd + ", system.slice " + pressure + " at " + limit
                    + ", swap " + (swap.isBlank() ? "none" : swap);
        }
    }

    /**
     * The verdict, out of everything the helper printed. The LAST marked state line wins for the
     * same reason the ipv6 helper's does: a rerun of the helper inside one phase would otherwise be
     * judged on its first answer.
     */
    static State read(List<String> output) {
        boolean systemdless = output.stream().anyMatch(line -> line.contains("systemd=absent"));
        String line = output.stream()
                .filter(text -> text.contains(MARKER + " state "))
                .reduce((first, last) -> last)
                .orElse("");
        return new State(field(line, "oomd"), field(line, "pressure"), field(line, "limit"),
                field(line, "swap-kill"), field(line, "swap"), systemdless);
    }

    /**
     * One {@code key=value} out of the verdict. {@code swap} is last on the line and may be several
     * words, so it reads to the end; every other key stops at the next space.
     */
    private static String field(String line, String key) {
        int at = line.indexOf(key + "=");
        if (at < 0) {
            return "";
        }
        String rest = line.substring(at + key.length() + 1);
        if ("swap".equals(key)) {
            return rest.trim();
        }
        int space = rest.indexOf(' ');
        return (space < 0 ? rest : rest.substring(0, space)).trim();
    }
}
