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
        String resolvedIdp = TokenClient.trim(idpUrl == null ? env("QITS_IDP_URL", "http://localhost:8080/idp") : idpUrl);
        // Git must use the edge origin. It is the boundary which turns Git's Basic oauth2 token
        // into the forwarded identity headers the raw githost routes enforce.
        String origin = GitOrigin.normalize(gitHost == null ? env("QITS_GIT_HOST_URL", "http://localhost:8080") : gitHost);
        String resolvedAudience = audience == null ? env("QITS_ENV_NAME", "prod") + "-qits-githost" : audience;
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
                    + "/login and run qits login again.");
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
        System.out.println("Configure Git once: git config --global credential.helper '!qits git-credential'");
        return 0;
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
