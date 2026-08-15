package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed file is a STACK file, and the four things swarm does not take are what this asserts.
 * Every one of them was measured against this host's docker rather than assumed: a root
 * {@code name:} key and a {@code group_add:} key each REFUSE the whole file, {@code container_name}
 * is accepted and ignored, and a publish with an ip in it is accepted with its address dropped.
 */
class StackFileTest {

    private static final String ENV = "prod";

    private static String stack() {
        return ComposeTemplate.compose(ComposeTemplateTest.tokens());
    }

    /**
     * The file with its comments taken out — what docker actually reads. The comments name every
     * key this file must not carry, so a test that asserts an absence has to ask the keys.
     */
    private static String keysOnly(String stack) {
        return stack.lines().filter(line -> !line.trim().startsWith("#"))
                .reduce("", (all, line) -> all + line + "\n");
    }

    /** One service's own lines: from its key to the next 2-space key that is not a comment. */
    private static String block(String stack, String service) {
        List<String> lines = stack.lines().toList();
        StringBuilder found = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            boolean key = line.startsWith("  ") && !line.startsWith("   ")
                    && !line.startsWith("  #") && line.endsWith(":");
            if (key) {
                if (inside) {
                    break;
                }
                inside = line.equals("  " + service + ":");
            }
            if (inside) {
                found.append(line).append('\n');
            }
        }
        assertThat(found.toString()).as("the block of %s", service).isNotEmpty();
        return found.toString();
    }

    private static String alias(String app) {
        return PlatformModel.wireAlias(app, ENV);
    }

    /** The stack name is the deploy command's argument, and the key is refused outright. */
    @Test
    void thereIsNoProjectNameKey() {
        assertThat(stack().lines()).noneMatch(line -> line.startsWith("name:"));
    }

    /**
     * A stack ignores it and names the container {@code <stack>_<service>.<slot>.<taskid>}, so a
     * name left here would read as an address that nothing answers to.
     */
    @Test
    void noServiceIsNamedByContainerName() {
        assertThat(keysOnly(stack())).doesNotContain("container_name");
    }

    /** Not merely unsupported: the loader refuses the file, so it cannot be left in to be ignored. */
    @Test
    void noServiceCarriesGroupAdd() {
        assertThat(keysOnly(stack())).doesNotContain("group_add");
    }

    /**
     * The socket group is delivered as the PRIMARY group instead, and 1001 is the images' own user
     * — naming a group means naming the user too.
     */
    @Test
    void theSocketHoldersGetTheirGroupThroughUser() {
        String stack = stack();

        assertThat(block(stack, alias("deployments"))).contains("user: \"1001:988\"");
        assertThat(block(stack, alias("containers"))).contains("user: \"1001:988\"");
        // And nobody else: a group this platform grants is a deliberate act, one service at a time.
        assertThat(stack.lines().filter(line -> line.contains("user: \"1001:")).count())
                .isEqualTo(2);
    }

    /** {@code restart:} is a compose word swarm ignores; the policy lives under deploy. */
    @Test
    void everyServiceRestartsThroughItsDeployBlock() {
        String stack = stack();

        assertThat(keysOnly(stack)).doesNotContain("restart: unless-stopped");
        for (String app : PlatformModel.CORE) {
            assertThat(block(stack, alias(app))).as("the deploy block of %s", app)
                    .contains("    deploy:")
                    .contains("      replicas: 1")
                    .contains("      restart_policy:")
                    .contains("        condition: any");
        }
    }

    /** No update_config anywhere: swarm's default order is stop-first, which is the only one these
     * services can take — each holds a volume or a host port a second task would collide on. */
    @Test
    void noServiceAsksForAnUpdateOrder() {
        assertThat(keysOnly(stack())).doesNotContain("update_config");
    }

    /** External, because the bootstrap creates it as an attachable overlay before any of this. */
    @Test
    void theNetworkIsExternalAndDeclaredOnce() {
        assertThat(stack()).contains("  qits-net:\n").contains("    name: qits-net\n")
                .contains("    external: true\n");
        assertThat(stack().lines().filter(line -> line.equals("    external: true")).count())
                .isEqualTo(1);
    }

    /**
     * <b>The one host port that is nobody's now.</b> Every consumer dials the alias on 5432, this
     * CLI included, and a swarm publish could not have kept the loopback bind in any case.
     */
    @Test
    void postgresPublishesNothing() {
        assertThat(block(stack(), alias("oci-postgresql"))).doesNotContain("ports:")
                .doesNotContain("5433");
    }

    /**
     * <b>The two host doors that are left, and they take different modes.</b> The edge's publish is
     * INGRESS — the swarm holds the port, so an edge cutover is start-first and its successor can
     * pull its own image through the predecessor. The nameserver stays {@code mode: host}: it is
     * per node, like a plain {@code docker run -p}, and a delegation reaches one machine.
     * <p>
     * The byte plane's three publishes are gone: registry, mirror and git host are reached through
     * the edge by name.
     */
    @Test
    void onlyTheEdgePublishesAndItGoesThroughIngress() {
        String stack = stack();

        assertThat(block(stack, alias("platform-edge"))).contains("""
                      - target: 8080
                        published: 8080
                        protocol: tcp
                        mode: ingress
                """.stripTrailing());
        for (String app : List.of("artifacts", "platform-mirror", "githost")) {
            assertThat(block(stack, alias(app))).as("the ports of %s", app)
                    .doesNotContain("ports:");
        }
    }

    /** No publish can say 127.0.0.1 any more: neither publish mode has an ip field. */
    @Test
    void nothingClaimsToBindLoopback() {
        assertThat(keysOnly(stack())).doesNotContain("127.0.0.1");
    }

    /** With a domain the edge gains 80 and 443 — and NOT the unauthenticated management port. */
    @Test
    void theTlsPortsAreHostModeAndTheManagementPortIsNotPublished() {
        String edge = block(ComposeTemplate.compose(ComposeTemplateTest.tokens("qits-dev.eu")),
                alias("platform-edge"));

        assertThat(edge).contains("published: 80").contains("published: 443")
                .contains("mode: host");
        assertThat(keysOnly(edge)).doesNotContain("9000");
    }

    // --- the subset a rerun deploys ---------------------------------------------------------------

    /**
     * {@code docker stack deploy} takes a file and no service list, so what a rerun starts is
     * decided by leaving services OUT of the file — and what is left out is what the deployer
     * already manages.
     */
    @Test
    void onlyKeepsTheNamedServicesAndTheFileTheyNeed() {
        String subset = ComposeTemplate.only(stack(),
                List.of("qits-platform-idp", alias("ci"), alias("oci-postgresql")));

        assertThat(subset).contains("  qits-platform-idp:\n")
                .contains("  " + alias("ci") + ":\n")
                .contains("  " + alias("oci-postgresql") + ":\n");
        assertThat(subset).doesNotContain("  " + alias("deployments") + ":\n")
                .doesNotContain("  " + alias("gateway") + ":\n")
                .doesNotContain("qits/deployments:latest");
        // The file the kept services need is still whole: the header, the network and the volumes.
        assertThat(subset).contains("networks:").contains("    external: true")
                .contains("  qits-oci-postgresql-data:").contains("A STACK FILE");
    }

    /** A service's comment paragraph is read with the service, so it travels with it. */
    @Test
    void aKeptServiceKeepsTheCommentAboveIt() {
        String subset = ComposeTemplate.only(stack(), List.of(alias("containers")));

        assertThat(subset).contains("THE CONTAINER ORCHESTRATOR")
                .doesNotContain("THE BUS, and last in this file");
    }

    /** Everything named, which is a cold boot, is the file itself rather than a copy of it. */
    @Test
    void keepingEveryServiceChangesNothingThatMatters() {
        String stack = stack();
        List<String> all = PlatformModel.CORE.stream().map(StackFileTest::alias).toList();

        assertThat(ComposeTemplate.only(stack, all).stripTrailing())
                .isEqualTo(stack.stripTrailing());
    }
}
