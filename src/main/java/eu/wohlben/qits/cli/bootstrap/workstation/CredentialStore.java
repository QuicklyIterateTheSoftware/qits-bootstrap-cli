package eu.wohlben.qits.cli.bootstrap.workstation;

import java.io.IOException;
import java.util.Optional;

/** Stores refresh tokens outside qits' files and Git's plaintext credential files. */
public interface CredentialStore {
    Optional<WorkstationCredential> find(String gitOrigin) throws IOException, InterruptedException;

    void save(WorkstationCredential credential) throws IOException, InterruptedException;

    void remove(String gitOrigin) throws IOException, InterruptedException;
}
