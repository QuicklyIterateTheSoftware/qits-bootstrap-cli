package eu.wohlben.qits.cli.bootstrap.workstation;

/** The one secret-record kept in the operating system's credential store. */
public record WorkstationCredential(String idpUrl, String audience, String gitOrigin, String refreshToken) {
}
