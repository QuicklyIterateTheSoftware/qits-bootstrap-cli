package eu.wohlben.qits.cli.bootstrap.platform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>The deploy-order constraints no declaration carries</b> (qits-3): the cutover reasons
 * {@link PlatformModel#DEPLOYABLES}' javadoc gives, as pairs in MODEL names. {@link BootOrder}
 * sorts the deploys over these plus the RUNTIME edges the declarations derive, and nothing else.
 * <p>
 * <b>A rule says only "before", never "immediately before".</b> Every adjacency the javadoc claims
 * — the edge second to last, the containers-ci pair, the byte plane's three side by side — is
 * dropped on purpose: a topological sort guarantees order, and an adjacency that matters would be
 * a constraint on what may sit between, which nothing here says. <b>"Observability first" is
 * dropped too</b>: quieting OTLP warnings early is a preference, not a cutover hazard.
 * <p>
 * <b>A rule a RUNTIME edge already implies is deleted, not kept as documentation</b>
 * ({@code CutoverRulesTest}): the declaration is the authority, and a duplicate would outlive it.
 * <p>
 * <b>{@link #EVERY_OTHER} expands at evaluation time, against the deploy set.</b> A rule
 * {@code * -> X} is every deploy before X — except X itself and every target of a wildcard rule
 * listed ABOVE it, which goes later still. So {@code * -> deployments} then {@code * -> edge}
 * reads "the deployer last, the edge last of the rest".
 */
public final class CutoverRules {

    /**
     * One constraint.
     *
     * @param before the model name that deploys first, or {@link #EVERY_OTHER}
     * @param after  the model name that deploys after it
     * @param reason why, quoted from {@link PlatformModel#DEPLOYABLES}
     */
    public record Rule(String before, String after, String reason) {}

    /** {@code before} of a rule that puts every other deploy before {@code after}. */
    public static final String EVERY_OTHER = "*";

    /** The rules, wildcards first in the order their targets go LAST to first. */
    public static final List<Rule> RULES = List.of(
            new Rule(EVERY_OTHER, "deployments",
                    "qits-deployments at the very end — its deployment is the self-update handoff."),
            new Rule(EVERY_OTHER, "edge",
                    "It is the door, so its cutover is the one deployment that takes THIS PROGRAM's"
                            + " own door away for a beat: every remaining public request depends"
                            + " on it."),
            new Rule("mirror", "artifacts",
                    "every image build and every dependency resolution after it goes through the"
                            + " mirror, so its cutover belongs before the services whose builds it"
                            + " feeds rather than in the middle of them."),
            new Rule("artifacts", "githost",
                    "The byte plane's three sit together, and their order inside it is forced."
                            + " qits-githost comes after qits-artifacts"),
            new Rule("githost", "ci",
                    "qits-githost comes after qits-artifacts and before qits-ci, because ci reads"
                            + " pipeline config out of the git host and clones from it"),
            new Rule("githost", "docs",
                    "It goes after qits-githost rather than between the byte plane's three"),
            new Rule("artifacts", "docs",
                    "qits-docs comes AFTER qits-artifacts, because it reads from it."),
            new Rule("docs", "containers",
                    "and before qits-containers, whose place against qits-ci is forced."),
            new Rule("containers", "ci",
                    "ci runs every pipeline step as a container it asks this service for, so a ci"
                            + " cutover landing while the orchestrator is mid-cutover is a pipeline"
                            + " with nowhere to run."),
            orchestrator("artifacts"),
            orchestrator("containers"),
            orchestrator("ci"),
            maintenance("projects"),
            maintenance("mirror"),
            maintenance("artifacts"),
            maintenance("githost"),
            maintenance("ci"),
            system("containers"),
            system("ci"),
            system("orchestrator"),
            system("maintenance"));

    private CutoverRules() {
    }

    private static Rule orchestrator(String before) {
        return new Rule(before, "orchestrator",
                "Its first process is the platform's unified deletion run, which drives"
                        + " qits-artifacts, qits-containers, qits-ci and the deployer, and each of"
                        + " those four is above it here. [...] it holds a scheduler, so a cutover"
                        + " that lands while a peer is mid-cutover is a run whose steps fail against"
                        + " a service that is being replaced.");
    }

    private static Rule maintenance(String before) {
        return new Rule(before, "maintenance",
                "It reads the catalog from qits-projects, the manifests from qits-githost, the"
                        + " versions from qits-artifacts and qits-platform-mirror, and it asks"
                        + " qits-ci to apply a bump — five callees, every one of them above it"
                        + " here.");
    }

    private static Rule system(String before) {
        return new Rule(before, "system",
                "It is last of the platform-tier applications because its own cutover DROPS every"
                        + " open terminal: put earlier, the successor would take the sessions of an"
                        + " operator watching the rest of the train go by.");
    }

    /** {@link #RULES} with every wildcard expanded into concrete pairs over {@code deploys}. */
    public static List<Rule> expand(List<Rule> rules, Set<String> deploys) {
        List<Rule> concrete = new ArrayList<>();
        Set<String> laterTargets = new LinkedHashSet<>();
        for (Rule rule : rules) {
            if (!EVERY_OTHER.equals(rule.before())) {
                concrete.add(rule);
                continue;
            }
            for (String deploy : deploys) {
                if (!deploy.equals(rule.after()) && !laterTargets.contains(deploy)) {
                    concrete.add(new Rule(deploy, rule.after(), rule.reason()));
                }
            }
            laterTargets.add(rule.after());
        }
        return List.copyOf(concrete);
    }
}
