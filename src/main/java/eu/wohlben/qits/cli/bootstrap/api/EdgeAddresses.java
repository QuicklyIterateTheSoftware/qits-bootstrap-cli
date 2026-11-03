package eu.wohlben.qits.cli.bootstrap.api;

import java.net.URI;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * <b>WHERE THIS RUN DIALS A PLATFORM SERVICE, and it changes once per run.</b>
 * <p>
 * Every platform call goes through the public edge, {@code https://<label>.qits.<domain>} — with
 * one exception, and this class is it. During a COLD BOOT there is no edge to go through yet: the
 * seed edge starts with a placeholder certificate and the deployed one comes last of the seed
 * deploys. So until the edge is ready this run dials what it always has, the service's own alias
 * on qits-net ({@code <env>-qits-<app>:8080}). From the moment {@code edge-ready} has proved the
 * edge serves a name, every call to that service goes to its public name instead, with the
 * bootstrap's own bearer, and stays there for the rest of the run: the switch is a LATCH. Nothing
 * here can flip a service back, because nothing here is allowed to.
 * <p>
 * <b>The signal is the {@code edge-ready} phase passing</b>, and only for the names it proved.
 * That phase asks the edge over HTTPS with the JVM's own trust store, at each public name, for a
 * path the service answers; a pass is a certificate this program trusts and a route the edge
 * serves. Anything weaker (the edge's own health on its alias) would prove the process is up, not
 * that its certificate is real or that it routes a given name — and a name the edge does not route
 * yet would turn every call into a 404 or 503 from then on. So a service whose name the phase did
 * not prove stays on its alias even after the latch: today that is qits-deployments, which is not
 * seed-deployed and so has no public route until its own self-update at the end of the train.
 * <p>
 * <b>The domain is the one the run already has, {@code QITS_DOMAIN}.</b> Without it there is no
 * public name to build, so the latch refuses, naming the setting.
 */
public final class EdgeAddresses {

    /** The platform project's label: its applications are {@code <label>.qits.<domain>}. */
    public static final String PROJECT = "qits";

    private final Supplier<Optional<String>> domain;

    /** Null until the edge is ready; then the names it proved, and only ever more of them. */
    private Set<String> served;
    private String servedDomain;

    /** @param domain the platform's domain, read when the latch is set and not before */
    public EdgeAddresses(Supplier<Optional<String>> domain) {
        this.domain = domain;
    }

    /** The message the latch refuses with when there is no domain to build a public name from. */
    public static String missingDomain() {
        return "QITS_DOMAIN (--domain) is not set: once the edge is ready this run dials every "
                + "platform service at https://<label>." + PROJECT + ".<domain>, and without a "
                + "domain there is no such name. Set QITS_DOMAIN to the domain the edge serves";
    }

    /**
     * <b>The edge is ready for these labels.</b> Called by {@code edge-ready} once it has proved
     * each name. Idempotent and only ever widening: a label already served stays served.
     *
     * @throws IllegalStateException when no domain is configured
     */
    public synchronized void ready(Collection<String> labels) {
        String value = domain.get().map(String::strip).filter(d -> !d.isEmpty())
                .orElseThrow(() -> new IllegalStateException(missingDomain()));
        if (served == null) {
            served = new TreeSet<>();
            servedDomain = value;
        }
        served.addAll(labels);
    }

    /** Whether the latch is set — for any label at all. */
    public synchronized boolean edgeReady() {
        return served != null;
    }

    /** Whether calls to {@code label} go through the edge now. */
    public synchronized boolean throughEdge(String label) {
        return served != null && served.contains(label);
    }

    /**
     * The base to dial a service at: {@code internal} before the edge is ready for its label, and
     * the same path on {@code https://<label>.qits.<domain>} after.
     *
     * @param label    the name the service publishes itself at ({@code ci}, {@code registry}, …)
     * @param internal its address on qits-net, path included ({@code http://dev-qits-ci:8080/ci})
     */
    public String base(String label, String internal) {
        String edgeDomain;
        synchronized (this) {
            if (served == null || !served.contains(label)) {
                return internal;
            }
            edgeDomain = servedDomain;
        }
        return "https://" + host(label, edgeDomain) + path(internal);
    }

    /** The same, asked per call: what an API class is built with. */
    public Supplier<String> route(String label, String internal) {
        return () -> base(label, internal);
    }

    /** {@code <label>.qits.<domain>}: one application of the platform project, at the edge. */
    public static String host(String label, String domain) {
        return label + "." + PROJECT + "." + domain;
    }

    /** The path of an internal address, which the edge serves unchanged under the public name. */
    static String path(String internal) {
        String path = URI.create(internal).getRawPath();
        if (path == null || path.equals("/")) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
