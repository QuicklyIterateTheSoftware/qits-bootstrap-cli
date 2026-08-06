package eu.wohlben.qits.cli.bootstrap.api;

import eu.wohlben.qits.cli.bootstrap.platform.Docker;
import eu.wohlben.qits.cli.bootstrap.proc.Cmd;
import eu.wohlben.qits.cli.bootstrap.proc.ProcessResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * qits-idp publishes no host port and sits on no gateway route on purpose: {@code /idp/token}
 * behind an unauthenticated gateway is a token vending machine.
 * <p>
 * The script reached it by joining qits-net — it was a container. This CLI runs on the host, so it
 * borrows a network position instead: one throwaway curl container per call, on qits-net. That
 * changes nothing about what the platform exposes, which is the property worth keeping.
 * <p>
 * It is also the fallback for a service that HAS a gateway route while the route is not up yet: a
 * seed health poll asks the gateway first and the container's own alias second.
 * <p>
 * The image reference is direct (docker.io), like the other bootstrap-only images: a cold start
 * cannot pull through the mirror it is starting.
 */
public class InNetworkHttp {

    private final Docker docker;
    private final String image;
    private final String network;

    public InNetworkHttp(Docker docker, String image, String network) {
        this.docker = docker;
        this.image = image;
        this.network = network;
    }

    public Http.Response get(String url) {
        return curl(List.of(url), null);
    }

    /** A form POST with basic auth — the shape of an OAuth2 client-credentials token request. */
    public Http.Response postForm(String url, String user, String password, Map<String, String> form) {
        List<String> args = new ArrayList<>(List.of("-X", "POST", "-u", user + ":" + password));
        form.forEach((key, value) -> {
            args.add("-d");
            args.add(key + "=" + value);
        });
        args.add(url);
        return curl(args, password);
    }

    private Http.Response curl(List<String> curlArgs, String secret) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm", "--network", network, image,
                "-sS", "-o", "-", "-w", "\n%{http_code}"));
        command.addAll(curlArgs);
        Cmd cmd = Cmd.of(command).timeout(Duration.ofMinutes(2));
        if (secret != null) {
            cmd.mask(secret);
        }
        ProcessResult result = docker.run(cmd, null);
        List<String> lines = result.captured();
        if (lines.isEmpty()) {
            return new Http.Response(0, "no output from " + image);
        }
        String last = lines.getLast().trim();
        int status;
        try {
            status = Integer.parseInt(last);
        } catch (NumberFormatException e) {
            return new Http.Response(0, String.join("\n", lines));
        }
        String body = String.join("\n", lines.subList(0, lines.size() - 1));
        return new Http.Response(status, body);
    }
}
