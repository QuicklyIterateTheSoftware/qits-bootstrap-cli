package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.cli.bootstrap.ingress.BootstrapIngressServer;
import io.vertx.core.Vertx;
import picocli.CommandLine;

import java.net.URI;
import java.util.concurrent.Callable;

/** The entrypoint run by the disposable, non-Swarm bootstrap ingress container. */
@CommandLine.Command(name = "bootstrap-edge", mixinStandardHelpOptions = true,
        description = "Run the temporary, capability-gated bootstrap ingress.")
public class BootstrapIngressCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        BootstrapIngressServer.Settings settings = new BootstrapIngressServer.Settings(
                requiredInt("QITS_BOOTSTRAP_INGRESS_INTERNAL_PORT"),
                required("QITS_BOOTSTRAP_INGRESS_HOST"),
                required("QITS_BOOTSTRAP_INGRESS_PASSWORD"),
                required("QITS_BOOTSTRAP_INGRESS_GITHOST_CAPABILITY"),
                URI.create(required("QITS_BOOTSTRAP_INGRESS_UI_UPSTREAM")),
                URI.create(required("QITS_BOOTSTRAP_INGRESS_GIT_UPSTREAM")), tls());
        Vertx vertx = Vertx.vertx();
        BootstrapIngressServer server = new BootstrapIngressServer(vertx, settings);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            vertx.close();
        }, "bootstrap-ingress-stop"));
        server.start().toCompletionStage().toCompletableFuture().get();
        // The process is the ingress. A signal closes the server through the hook above.
        Thread.currentThread().join();
        return 0;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static int requiredInt(String name) {
        try {
            return Integer.parseInt(required(name));
        } catch (NumberFormatException badPort) {
            throw new IllegalArgumentException(name + " must be an integer", badPort);
        }
    }

    private static BootstrapIngressServer.Tls tls() {
        String certificate = System.getenv("QITS_BOOTSTRAP_INGRESS_TLS_CERTIFICATE");
        String key = System.getenv("QITS_BOOTSTRAP_INGRESS_TLS_KEY");
        if ((certificate == null || certificate.isBlank()) && (key == null || key.isBlank())) {
            return null;
        }
        return new BootstrapIngressServer.Tls(requiredInt("QITS_BOOTSTRAP_INGRESS_TLS_PORT"),
                required("QITS_BOOTSTRAP_INGRESS_TLS_CERTIFICATE"),
                required("QITS_BOOTSTRAP_INGRESS_TLS_KEY"));
    }
}
