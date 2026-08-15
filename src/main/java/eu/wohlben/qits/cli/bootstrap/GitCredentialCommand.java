package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.cli.bootstrap.workstation.CredentialStore;
import eu.wohlben.qits.cli.bootstrap.workstation.GitOrigin;
import eu.wohlben.qits.cli.bootstrap.workstation.SecretToolCredentialStore;
import eu.wohlben.qits.cli.bootstrap.workstation.TokenClient;
import eu.wohlben.qits.cli.bootstrap.workstation.WorkstationCredential;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/** Implements Git's credential-helper protocol and supplies a freshly rotated access token. */
@CommandLine.Command(name = "git-credential", mixinStandardHelpOptions = true,
        description = "Git credential helper for qits workstation login.")
public class GitCredentialCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", defaultValue = "get", description = "Git credential action")
    String action;

    @Override
    public Integer call() throws Exception {
        Map<String, String> request = readRequest();
        String origin;
        try {
            origin = GitOrigin.fromGitRequest(request.get("protocol"), request.get("host"));
        } catch (IllegalArgumentException absent) {
            return 0;
        }
        CredentialStore store = new SecretToolCredentialStore();
        if ("erase".equals(action)) {
            store.remove(origin);
            return 0;
        }
        if (!"get".equals(action)) {
            // Git's later `store` is intentionally ignored: only qits login can write a refresh token.
            return 0;
        }
        Optional<WorkstationCredential> saved = store.find(origin);
        if (saved.isEmpty()) {
            return 0;
        }
        TokenClient.Token token = new TokenClient().refresh(saved.get());
        // Refresh token rotation is persisted before returning the access token to Git.
        store.save(new WorkstationCredential(saved.get().idpUrl(), saved.get().audience(), origin, token.refreshToken()));
        System.out.print("username=oauth2\npassword=" + token.accessToken() + "\n\n");
        return 0;
    }

    static Map<String, String> readRequest() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = input.readLine()) != null && !line.isEmpty();) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    result.put(line.substring(0, separator), line.substring(separator + 1));
                }
            }
        }
        return result;
    }
}
