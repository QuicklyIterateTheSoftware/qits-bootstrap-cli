package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.cli.bootstrap.workstation.CredentialStore;
import eu.wohlben.qits.cli.bootstrap.workstation.GitOrigin;
import eu.wohlben.qits.cli.bootstrap.workstation.LoopbackCallback;
import eu.wohlben.qits.cli.bootstrap.workstation.Pkce;
import eu.wohlben.qits.cli.bootstrap.workstation.SecretToolCredentialStore;
import eu.wohlben.qits.cli.bootstrap.workstation.TokenClient;
import eu.wohlben.qits.cli.bootstrap.workstation.WorkstationCredential;
import picocli.CommandLine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** Authorizes this workstation for the deliberately constrained external Git namespace. */
@CommandLine.Command(name = "login", mixinStandardHelpOptions = true,
        description = "Log this workstation in for external Git pushes.")
public class LoginCommand implements Callable<Integer> {
    @CommandLine.Option(names = "--idp-url", description = "IdP public base URL. Default: ${DEFAULT-VALUE}")
    String idpUrl;

    @CommandLine.Option(names = "--git-host", description = "Git HTTP origin. Default: ${DEFAULT-VALUE}")
    String gitHost;

    @CommandLine.Option(names = "--audience", description = "Githost OAuth audience. Default: <env>-qits-githost")
    String audience;

    @CommandLine.Option(names = "--timeout", description = "Seconds to wait for browser login. Default: 300")
    long timeout = 300;

    @Override
    public Integer call() throws Exception {
        // EVERY SERVICE HAS A HOST OF ITS OWN, the idp and the git host included, and EVERY PUBLIC
        // NAME SPELLS ITS ENVIRONMENT: idp.<env>.<domain> and githost.<env>.<domain> with a domain,
        // idp.<env>.localhost and githost.<env>.localhost without one. The door serves neither — it
        // redirects / to the projects host and 404s every path — so both defaults name the service
        // host directly.
        //
        // QITS_DOMAIN is read the same way QITS_ENV_NAME is: set, this workstation talks to a
        // domain platform over TLS; unset, to the local one on the edge's port.
        String domain = env("QITS_DOMAIN", "");
        String environment = environmentName(domain, System.getenv("QITS_ENV_NAME"));
        if (environment == null) {
            System.err.println(environmentRefusal(domain));
            return 2;
        }
        String idpHost = serviceHost("idp", domain, environment);
        String gitHostDefault = serviceHost("githost", domain, environment);
        String resolvedIdp = TokenClient.trim(idpUrl == null ? env("QITS_IDP_URL", idpHost + "/idp") : idpUrl);
        // Git goes through the edge, at the git host's own name. The edge is the boundary which
        // turns Git's Basic oauth2 token into the forwarded identity headers the raw githost routes
        // enforce.
        String origin = GitOrigin.normalize(gitHost == null ? env("QITS_GIT_HOST_URL", gitHostDefault) : gitHost);
        String resolvedAudience = audience == null ? environment + "-qits-githost" : audience;
        Pkce pkce = Pkce.create();
        String state = Pkce.state();
        try (LoopbackCallback callback = LoopbackCallback.open()) {
            String authorize = resolvedIdp + "/authorize?" + form(Map.of(
                    "response_type", "code", "client_id", TokenClient.CLIENT_ID,
                    "redirect_uri", callback.redirectUri(), "code_challenge", pkce.challenge(),
                    "code_challenge_method", "S256", "audience", resolvedAudience, "state", state));
            System.out.println("Opening your browser to sign in to qits…");
            openBrowser(authorize);
            System.out.println("If it says that no session exists, sign in at " + resolvedIdp
                    + "/login and run qits-bootstrap login again.");
            LoopbackCallback.Callback result = callback.await(Duration.ofSeconds(timeout));
            if (!state.equals(result.state()) || result.code() == null
                    || (result.error() != null && !result.error().isEmpty())) {
                System.err.println("The IdP did not complete the requested login.");
                return 1;
            }
            TokenClient.Token token = new TokenClient().exchange(resolvedIdp, result.code(), callback.redirectUri(), pkce.verifier());
            CredentialStore store = new SecretToolCredentialStore();
            store.save(new WorkstationCredential(resolvedIdp, resolvedAudience, origin, token.refreshToken()));
        }
        System.out.println("This workstation is ready for Git pushes to " + origin + ".");
        System.out.println("Configure Git once: git config --global credential.helper '!qits-bootstrap git-credential'");
        return 0;
    }

    /**
     * One service's public origin, and <b>both arms spell the environment</b>:
     * {@code https://<app>.<env>.<domain>} when {@code QITS_DOMAIN} is set,
     * {@code http://<app>.<env>.localhost:8080} otherwise.
     * <p>
     * The short {@code <app>.<domain>} form is retired. It used to reach the default environment by
     * fallthrough — the edge read two labels and gave an unrecognised one to the default tier — and
     * that reading is gone with the project tier: every public name says which environment it wants,
     * and only the bare apex still serves the default one.
     */
    static String serviceHost(String app, String domain, String environment) {
        return domain == null || domain.isBlank()
                ? "http://" + app + "." + environment + ".localhost:8080"
                : "https://" + app + "." + environment + "." + domain;
    }

    /**
     * <b>The environment name, or null when this run may not guess one.</b>
     * <p>
     * {@code QITS_ENV_NAME} decides it. Unset, the LOCAL arm keeps its {@code prod} default: that
     * name has always been in the local hostname ({@code idp.prod.localhost}) and this change did
     * not move it, so a wrong value there fails the way it always did, against a platform on the
     * caller's own machine.
     * <p>
     * <b>With a domain there is no default that is safe, so there is none.</b> The environment used
     * to be absent from the domain-arm hostname altogether — {@code idp.<domain>} reached whichever
     * tier the edge called default — and it is in the name now. A guessed {@code prod} against a
     * platform whose environment is called something else does not fail at the resolver: the zone's
     * wildcards answer every shape, so the request reaches the edge, which reads {@code prod} as no
     * environment it has and hands it to the apex. What comes back is a confusing 404 from the right
     * host, and the thing being got wrong is which platform this workstation is being logged in to.
     * So it is refused before the browser is opened.
     *
     * @param configured {@code QITS_ENV_NAME} as the environment gives it, null or blank when unset
     */
    static String environmentName(String domain, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        return domain == null || domain.isBlank() ? "prod" : null;
    }

    /** What the refusal says, and it names the one value that fixes it. */
    static String environmentRefusal(String domain) {
        return "QITS_DOMAIN is set to '" + domain + "' and QITS_ENV_NAME is not, and on a domain "
                + "platform every public name spells its environment: this workstation would ask "
                + "idp.<env>." + domain + " and githost.<env>." + domain + " without knowing what "
                + "<env> is. There is no safe default — a guess resolves, reaches the edge and "
                + "comes back a 404 from the right host, which reads as a broken platform rather "
                + "than as a wrong name. Set QITS_ENV_NAME to that platform's environment (the "
                + "value its bootstrap was given as --platform-env) and run `qits-bootstrap login` again.";
    }

    private static void openBrowser(String url) throws Exception {
        // This CLI's workstation image is Linux. Avoid java.awt here: its native-image support pulls
        // in a desktop toolkit merely to open one URL, while xdg-open delegates to the user's browser.
        new ProcessBuilder("xdg-open", url).start();
    }

    private static String form(Map<String, String> values) {
        Map<String, String> ordered = new LinkedHashMap<>(values);
        return ordered.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).reduce((a, b) -> a + "&" + b).orElse("");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
