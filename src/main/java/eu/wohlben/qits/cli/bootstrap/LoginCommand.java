package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.cli.bootstrap.config.DomainName;
import eu.wohlben.qits.cli.bootstrap.platform.PlatformModel;
import eu.wohlben.qits.cli.bootstrap.workstation.CredentialStore;
import eu.wohlben.qits.cli.bootstrap.workstation.GitOrigin;
import eu.wohlben.qits.cli.bootstrap.workstation.LoopbackCallback;
import eu.wohlben.qits.cli.bootstrap.workstation.Pkce;
import eu.wohlben.qits.cli.bootstrap.workstation.SecretToolCredentialStore;
import eu.wohlben.qits.cli.bootstrap.workstation.TokenClient;
import eu.wohlben.qits.cli.bootstrap.workstation.WorkstationCredential;
import org.eclipse.microprofile.config.ConfigProvider;
import picocli.CommandLine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;

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
        // NAME IS READ RIGHT TO LEFT: <app>[.<env>].<project>.<domain>. This platform is the
        // project called `qits`, so the names are idp.<env>.qits.<domain> and
        // githost.<env>.qits.<domain>. There is no second arm any more: since 2026.1001.41945 a
        // platform cannot be bootstrapped without QITS_DOMAIN (BootstrapConfig.requiredDomain), so a
        // platform this command could reach under a localhost name does not exist. Both QITS_DOMAIN
        // and QITS_ENV_NAME are therefore read as settled facts of the platform being logged in to,
        // not as a switch between two kinds of platform, and both are required.
        //
        // The lookup goes through MicroProfile Config rather than System.getenv: Quarkus reads a
        // `.env` file in the working directory as a config source, the way the rest of this CLI's
        // knobs are read (BootstrapConfig), and System.getenv never sees that file. Reading the raw
        // environment here would silently miss a QITS_DOMAIN set only in `.env`.
        Settings settings = Settings.resolve(name -> ConfigProvider.getConfig().getOptionalValue(name, String.class));
        if (settings.domain().isBlank()) {
            System.err.println(DOMAIN_REFUSAL);
            return 2;
        }
        String domain;
        try {
            domain = DomainName.checked(settings.domain());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        String environment = environmentName(settings.environment());
        if (environment == null) {
            System.err.println(environmentRefusal(domain));
            return 2;
        }
        String idpHost = serviceHost("idp", domain, environment);
        String gitHostDefault = serviceHost("githost", domain, environment);
        String resolvedIdp = TokenClient.trim(idpUrl == null
                ? settings.idpUrl().orElse(idpHost + "/idp") : idpUrl);
        // Git goes through the edge, at the git host's own name. The edge is the boundary which
        // turns Git's Basic oauth2 token into the forwarded identity headers the raw githost routes
        // enforce.
        String origin = GitOrigin.normalize(gitHost == null
                ? settings.gitHostUrl().orElse(gitHostDefault) : gitHost);
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
     * One service's public origin, <b>carrying the platform's PROJECT label as well as its
     * environment</b>: {@code https://<app>.<env>.qits.<domain>}.
     * <p>
     * Names are read right to left — {@code <app>.<env>.<project>.<domain>}, each label inside the
     * one to its right — and the project label is MANDATORY: there is no unqualified application
     * tier and no top-level {@code <env>.<domain>} tier. The platform this command logs a
     * workstation in to is simply the project called {@code qits}, which is why that slug is spelled
     * here and nowhere else in this file.
     */
    static String serviceHost(String app, String domain, String environment) {
        return "https://" + app + "." + environment + "." + PlatformModel.PROJECT + "." + domain;
    }

    /**
     * <b>The environment name, or null when it is not configured.</b>
     * <p>
     * {@code QITS_ENV_NAME} decides it, and there is no default: every public name spells the
     * environment it reaches — {@code idp.<env>.qits.<domain>} — and a guessed one resolves,
     * reaches the edge and comes back a 404 from the right host, which reads as a broken platform
     * rather than as a wrong name. So it is refused before the browser is opened rather than guessed.
     *
     * @param configured {@code QITS_ENV_NAME} as the environment gives it, null or blank when unset
     */
    static String environmentName(String configured) {
        return configured != null && !configured.isBlank() ? configured.strip() : null;
    }

    /** What the refusal says, and it names the one value that fixes it. */
    static String environmentRefusal(String domain) {
        return "QITS_DOMAIN is set to '" + domain + "' and QITS_ENV_NAME is not, and every public "
                + "name spells its environment: this workstation would ask "
                + "idp.<env>." + PlatformModel.PROJECT + "." + domain + " and githost.<env>."
                + PlatformModel.PROJECT + "." + domain + " without knowing what "
                + "<env> is. There is no safe default — a guess resolves, reaches the edge and "
                + "comes back a 404 from the right host, which reads as a broken platform rather "
                + "than as a wrong name. Set QITS_ENV_NAME to that platform's environment (the "
                + "value its bootstrap was given as --platform-env) and run `qits-bootstrap login` again.";
    }

    /**
     * <b>What is said when {@code QITS_DOMAIN} is not set at all.</b> Since release
     * 2026.1001.41945 a platform cannot be bootstrapped without a domain
     * ({@code DomainName.missingRefusal}, {@code BootstrapConfig.requiredDomain}), so there is no
     * platform this command could still reach without one — the refusal fires before the browser
     * opens rather than reaching for a host that answers to nobody.
     */
    static final String DOMAIN_REFUSAL = "QITS_DOMAIN is not set, and login has no platform to ask: "
            + "a platform cannot be bootstrapped without a domain any more, so there is no name left "
            + "to guess at. Set QITS_DOMAIN in .env or in the environment to the domain this platform "
            + "was bootstrapped with (--domain), and run `qits-bootstrap login` again.";

    /**
     * <b>The knobs this command reads before anything else runs, resolved through one lookup
     * function.</b> {@code call()} feeds it MicroProfile Config — which, unlike
     * {@code System.getenv}, also sees a {@code .env} file in the working directory, the same
     * source {@code BootstrapConfig} reads — so a test can feed it values of its own without
     * touching the real environment.
     *
     * @param domain the configured {@code qits.domain}, stripped; blank when unset
     * @param environment the configured {@code qits.env-name}, stripped; null when unset or blank
     * @param idpUrl the configured {@code qits.idp-url}, stripped; empty when unset or blank
     * @param gitHostUrl the configured {@code qits.git-host-url}, stripped; empty when unset or blank
     */
    record Settings(String domain, String environment, Optional<String> idpUrl, Optional<String> gitHostUrl) {
        static Settings resolve(Function<String, Optional<String>> lookup) {
            return new Settings(
                    lookup.apply("qits.domain").map(String::strip).orElse(""),
                    lookup.apply("qits.env-name").map(String::strip).filter(value -> !value.isEmpty()).orElse(null),
                    lookup.apply("qits.idp-url").map(String::strip).filter(value -> !value.isEmpty()),
                    lookup.apply("qits.git-host-url").map(String::strip).filter(value -> !value.isEmpty()));
        }
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
}
