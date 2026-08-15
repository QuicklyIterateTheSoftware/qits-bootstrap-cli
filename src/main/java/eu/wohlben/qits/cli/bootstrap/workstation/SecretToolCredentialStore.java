package eu.wohlben.qits.cli.bootstrap.workstation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Linux Secret Service storage through libsecret's {@code secret-tool}. The token is sent on
 * stdin, never in an argument, environment variable, qits config file, or Git credential file.
 */
public final class SecretToolCredentialStore implements CredentialStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SERVICE = "qits-git-workstation";

    @Override
    public Optional<WorkstationCredential> find(String gitOrigin) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("secret-tool", "lookup", "qits-service", SERVICE,
                    "git-origin", gitOrigin).start();
        } catch (IOException unavailable) {
            throw unavailable();
        }
        String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();
        if (exit == 1 || value.isBlank()) {
            return Optional.empty();
        }
        if (exit != 0) {
            throw new IOException("could not read the workstation credential from Secret Service");
        }
        return Optional.of(JSON.readValue(value, WorkstationCredential.class));
    }

    @Override
    public void save(WorkstationCredential credential) throws IOException, InterruptedException {
        String value = JSON.writeValueAsString(credential);
        Process process;
        try {
            process = new ProcessBuilder("secret-tool", "store", "--label=qits Git workstation credential",
                    "qits-service", SERVICE, "git-origin", credential.gitOrigin()).start();
        } catch (IOException unavailable) {
            throw unavailable();
        }
        try (OutputStream input = process.getOutputStream()) {
            input.write(value.getBytes(StandardCharsets.UTF_8));
        }
        if (process.waitFor() != 0) {
            throw new IOException("could not save the workstation credential in Secret Service");
        }
    }

    @Override
    public void remove(String gitOrigin) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("secret-tool", "clear", "qits-service", SERVICE,
                    "git-origin", gitOrigin).start();
        } catch (IOException unavailable) {
            throw unavailable();
        }
        if (process.waitFor() != 0) {
            throw new IOException("could not remove the workstation credential from Secret Service");
        }
    }

    private static IOException unavailable() {
        return new IOException("No secure credential store is available: install libsecret's secret-tool. "
                + "qits deliberately never writes workstation refresh tokens to disk.");
    }
}
