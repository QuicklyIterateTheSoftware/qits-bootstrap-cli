package eu.wohlben.qits.cli.bootstrap.contracts;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.cli.bootstrap.api.BootstrapPublishCredential;
import eu.wohlben.qits.cli.bootstrap.api.ConfigurationApi;
import eu.wohlben.qits.cli.bootstrap.api.EventsApi;
import eu.wohlben.qits.cli.bootstrap.api.Http;
import eu.wohlben.qits.cli.bootstrap.api.IdpApi;
import eu.wohlben.qits.cli.bootstrap.api.ProjectsApi;
import eu.wohlben.qits.cli.bootstrap.workstation.TokenClient;
import eu.wohlben.qits.cli.bootstrap.workstation.WorkstationCredential;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>No call to a qits service without its row</b> (qits-1149): the set of calls is read off the
 * code, never off {@link BootstrapContract} itself.
 * <p>
 * <b>The API classes, by running them.</b> Every public instance method of every
 * {@code api/*Api} class, of {@link BootstrapPublishCredential} and of {@link TokenClient} is
 * invoked against a local server that records each request and answers 200 with a body every
 * reader accepts. A request is covered when a row of the same method has a path template it fits
 * and the same query names. A method that sends nothing (an accessor, a url builder) records
 * nothing and needs nothing. A new {@code *Api} class fails here until it has a factory below, so
 * it cannot slip past by not being constructed.
 * <p>
 * <b>The phases, by reading them.</b> A phase that dials {@code boot.http} directly, or builds an
 * {@code HttpRequest} of its own, is not inside an API class for the first half to run; every such
 * site in {@code src/main} has to be one of {@link #DIRECT_SITES}, each naming the row it is or
 * why it is none.
 * <p>
 * <b>The exclusions are named, each with its reason</b>, and a stale one fails like a missing row.
 */
class BootstrapCallGuardTest {

    /** Requests no row is owed for, by the path they end in, and why. */
    static final Map<String, String> EXCLUDED_PATHS = Map.of(
            "/q/health/ready", "a readiness probe: Quarkus' own health route, no API of the service");

    /** Public API methods no phase calls, so no request of theirs is ever made. */
    static final Map<String, String> UNCALLED_METHODS = Map.of(
            "CiApi.finishedEventRun", "superseded by greenReleaseRunAt; called by no phase",
            "CiApi.finishedEventRuns", "superseded by greenReleaseRunAt; called by no phase",
            "CiApi.newestRun", "called by no phase",
            "ServiceClientsApi.source", "called by no phase, only by its unit test");

    /**
     * The direct call sites outside the API classes: a fragment of the call's own text, and the row
     * it is ({@code provider operationId status}) or the reason it is none.
     */
    static final Map<String, String> DIRECT_SITES = Map.ofEntries(
            Map.entry("/q/health/ready", "excluded: a readiness probe"),
            Map.entry("/api/events/trigger", "row: qits-ci-service triggerEvent 401"),
            Map.entry("/api/events/software-released",
                    "row: qits-deployments-service softwareReleased 401"),
            Map.entry("/containers/api/containers/",
                    "row: qits-containers-service listOwnerContainers 401"),
            Map.entry("new EdgeProbe(url",
                    "excluded: edge-ready asks whether a public name answers over TLS at all —"
                            + " any status is an answer, so no service's API is read"),
            Map.entry("postJson(certs",
                    "excluded: the edge's Quarkus management interface reloading its certificate"
                            + " (:9000/q/lets-encrypt/certs), framework-owned and no API"));

    private static final Pattern DIRECT_CALL = Pattern.compile(
            "\\bhttp\\.(get|head|delete|post|postForm|postJson|postText|patchJson|putJson|putFile)\\("
                    + "|HttpRequest\\.newBuilder\\(");

    /** The body every reader in the API classes accepts as a success. */
    private static final String ANSWER = "{\"access_token\":\"t\",\"refresh_token\":\"r\","
            + "\"expires_in\":60,\"clientId\":\"c\",\"secret\":\"s\",\"token\":\"t\"}";

    private static HttpServer server;
    private static String base;
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String query = exchange.getRequestURI().getRawQuery();
            REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + (query == null ? "" : "?" + query));
            byte[] body = ANSWER.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    /** Each client class, built at the address its rows are written against. */
    private static Map<Class<?>, Supplier<Object>> factories() {
        Map<Class<?>, Supplier<Object>> factories = new LinkedHashMap<>();
        factories.put(eu.wohlben.qits.cli.bootstrap.api.ArtifactsApi.class,
                () -> BootstrapContract.artifacts(base));
        factories.put(eu.wohlben.qits.cli.bootstrap.api.CiApi.class,
                () -> BootstrapContract.ci(base));
        factories.put(ConfigurationApi.class, () -> new ConfigurationApi(new Http(), base, "dev"));
        factories.put(EventsApi.class, () -> new EventsApi(new Http(), base + "/events"));
        factories.put(eu.wohlben.qits.cli.bootstrap.api.GitHostApi.class,
                () -> BootstrapContract.githost(base));
        factories.put(IdpApi.class, () -> new IdpApi(new Http(), base + "/idp"));
        factories.put(eu.wohlben.qits.cli.bootstrap.api.PdApi.class,
                () -> BootstrapContract.pd(base));
        factories.put(ProjectsApi.class, () -> new ProjectsApi(new Http(), base));
        factories.put(eu.wohlben.qits.cli.bootstrap.api.ServiceClientsApi.class,
                () -> BootstrapContract.serviceClients(base));
        factories.put(eu.wohlben.qits.cli.bootstrap.api.SystemApi.class,
                () -> BootstrapContract.system(base));
        factories.put(BootstrapPublishCredential.class, () -> new BootstrapPublishCredential(
                new Http(), new IdpApi(new Http(), base + "/idp"), base + "/idp", "qits-bootstrap",
                "secret", "dev", "qits-platform"));
        factories.put(TokenClient.class, TokenClient::new);
        return factories;
    }

    @Test
    void everyRequestAnApiClassMakesHasARow() throws Exception {
        Map<Class<?>, Supplier<Object>> factories = factories();
        List<String> failures = new ArrayList<>();
        for (Class<?> api : apiClasses()) {
            if (!factories.containsKey(api)) {
                failures.add(api.getSimpleName() + " is an API class the guard cannot build: give it"
                        + " a factory in BootstrapCallGuardTest.factories()");
            }
        }
        Set<String> uncalled = new TreeSet<>(UNCALLED_METHODS.keySet());
        Set<String> excludedSeen = new TreeSet<>();
        for (Map.Entry<Class<?>, Supplier<Object>> entry : factories.entrySet()) {
            Object client = entry.getValue().get();
            for (Method method : publicMethods(entry.getKey())) {
                String name = entry.getKey().getSimpleName() + "." + method.getName();
                if (UNCALLED_METHODS.containsKey(name)) {
                    uncalled.remove(name);
                    continue;
                }
                REQUESTS.clear();
                invoke(client, method);
                for (String request : REQUESTS) {
                    String path = request.substring(request.indexOf(' ') + 1).split("\\?")[0];
                    String excluded = EXCLUDED_PATHS.keySet().stream().filter(path::endsWith)
                            .findFirst().orElse(null);
                    if (excluded != null) {
                        excludedSeen.add(excluded);
                    } else if (!covered(request)) {
                        failures.add(name + " sends " + request + ", and no row of"
                                + " BootstrapContract is that call");
                    }
                }
            }
        }
        uncalled.forEach(m -> failures.add("UNCALLED_METHODS names " + m + ", which no API class"
                + " has any more"));
        new TreeSet<>(EXCLUDED_PATHS.keySet()).stream().filter(p -> !excludedSeen.contains(p))
                .forEach(p -> failures.add("EXCLUDED_PATHS names " + p + ", which nothing sends"));
        if (!failures.isEmpty()) {
            fail(String.join("\n", failures));
        }
    }

    /** What {@link #UNCALLED_METHODS} says, checked: no main source outside the class calls one. */
    @Test
    void theUncalledMethodsAreCalledByNothing() throws IOException {
        List<String> failures = new ArrayList<>();
        for (String qualified : UNCALLED_METHODS.keySet()) {
            String owner = qualified.substring(0, qualified.indexOf('.')) + ".java";
            Pattern call = Pattern.compile("\\." + Pattern.quote(
                    qualified.substring(qualified.indexOf('.') + 1)) + "\\(");
            for (Path source : mainSources()) {
                if (source.getFileName().toString().equals(owner)) {
                    continue;
                }
                if (call.matcher(Files.readString(source)).find()) {
                    failures.add(qualified + " is called from " + source.getFileName()
                            + ": it needs a row, not an exclusion");
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(String.join("\n", failures));
        }
    }

    @Test
    void everyDirectCallSiteIsNamed() throws IOException {
        List<String> failures = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        Path api = sourceRoot().resolve("eu/wohlben/qits/cli/bootstrap/api");
        Path tokenClient = sourceRoot().resolve("eu/wohlben/qits/cli/bootstrap/workstation/"
                + "TokenClient.java");
        for (Path source : mainSources()) {
            if (source.startsWith(api) || source.equals(tokenClient)) {
                continue;
            }
            String text = Files.readString(source);
            Matcher m = DIRECT_CALL.matcher(text);
            while (m.find()) {
                // The call's own text: from the receiver to the end of its statement.
                int start = Math.max(text.lastIndexOf('\n', m.start()), m.start() - 200);
                int end = text.indexOf(';', m.end());
                String call = text.substring(Math.max(0, start), end < 0 ? text.length() : end);
                String site = DIRECT_SITES.keySet().stream().filter(call::contains)
                        .findFirst().orElse(null);
                if (site == null) {
                    failures.add(source.getFileName() + ": " + call.strip()
                            + "\n  is an HTTP call outside the API classes that DIRECT_SITES does"
                            + " not name");
                    continue;
                }
                seen.add(site);
                String verdict = DIRECT_SITES.get(site);
                if (verdict.startsWith("row: ") && !rowExists(verdict.substring(5))) {
                    failures.add(source.getFileName() + ": " + site + " names " + verdict
                            + ", which BootstrapContract does not hold");
                }
            }
        }
        DIRECT_SITES.keySet().stream().filter(s -> !seen.contains(s)).sorted()
                .forEach(s -> failures.add("DIRECT_SITES names '" + s + "', which no call has"));
        if (!failures.isEmpty()) {
            fail(String.join("\n", failures));
        }
    }

    // --- the comparison ---------------------------------------------------------------------------

    /** Whether a row is this request: the method, a path the template fits, the same query names. */
    private static boolean covered(String request) {
        String method = request.substring(0, request.indexOf(' '));
        String target = request.substring(request.indexOf(' ') + 1);
        String path = target.split("\\?", 2)[0];
        Set<String> query = new TreeSet<>();
        if (target.contains("?")) {
            for (String pair : target.split("\\?", 2)[1].split("&")) {
                query.add(pair.split("=", 2)[0]);
            }
        }
        for (BootstrapContract.Case row : BootstrapContract.CASES) {
            BootstrapContract.Request r = row.request();
            if (r.method().equals(method) && template(r.path()).matcher(path).matches()
                    && new TreeSet<>(r.query().keySet()).equals(query)) {
                return true;
            }
        }
        return false;
    }

    private static Pattern template(String path) {
        StringBuilder regex = new StringBuilder();
        Matcher m = Pattern.compile("\\{[A-Za-z0-9_]+}").matcher(path);
        int at = 0;
        while (m.find()) {
            regex.append(Pattern.quote(path.substring(at, m.start()))).append("[^/]+");
            at = m.end();
        }
        return Pattern.compile(regex.append(Pattern.quote(path.substring(at))).toString());
    }

    private static boolean rowExists(String spec) {
        String[] parts = spec.split(" ");
        return BootstrapContract.CASES.stream().anyMatch(c -> c.provider().repository()
                .equals(parts[0]) && c.operationId().equals(parts[1])
                && c.status() == Integer.parseInt(parts[2]));
    }

    // --- running the clients ----------------------------------------------------------------------

    /** Instance methods declared public on the class itself; lifecycle ends ({@code close}) last. */
    private static List<Method> publicMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())
                        && !m.isSynthetic())
                .filter(m -> !Set.of("toString", "hashCode", "equals").contains(m.getName()))
                .sorted(Comparator.comparing((Method m) -> Set.of("close", "delete")
                                .contains(m.getName()))
                        .thenComparing(Method::getName)
                        .thenComparing(m -> Arrays.toString(m.getParameterTypes())))
                .toList();
    }

    private static void invoke(Object client, Method method) throws Exception {
        Object[] args = new Object[method.getParameterCount()];
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < args.length; i++) {
            args[i] = argument(parameters[i], i);
        }
        try {
            method.invoke(client, args);
        } catch (InvocationTargetException refused) {
            // A client that throws on what it was answered has still made its request.
        }
    }

    /**
     * A value of each parameter type the clients take. Two strings are not free text: a url is this
     * server's idp (TokenClient is told where the idp is per call), and a maven file's extension is
     * {@code jar}, the only one any phase asks for.
     */
    private static Object argument(Parameter parameter, int index) throws IOException {
        Class<?> type = parameter.getType();
        if (type == String.class) {
            if (parameter.getName().endsWith("Url")) {
                return base + "/idp";
            }
            return "extension".equals(parameter.getName()) ? "jar" : "p" + index;
        }
        if (type == int.class || type == Integer.class) {
            return 2;
        }
        if (type == boolean.class || type == Boolean.class) {
            return true;
        }
        if (type == Path.class) {
            Path file = Files.createTempFile("qits-guard", "");
            Files.writeString(file, "x");
            return file;
        }
        if (type == WorkstationCredential.class) {
            return new WorkstationCredential(base + "/idp", "qits-platform", null, "r");
        }
        if (type == Supplier.class) {
            return (Supplier<String>) () -> "Bearer guard";
        }
        throw new IllegalStateException(parameter.getDeclaringExecutable() + " takes a "
                + type.getName() + ", which BootstrapCallGuardTest cannot make up");
    }

    /** Every top-level {@code *Api} class compiled into the api package. */
    private static List<Class<?>> apiClasses() throws IOException, URISyntaxException,
            ClassNotFoundException {
        Path classes = Path.of(Http.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path dir = classes.resolve("eu/wohlben/qits/cli/bootstrap/api");
        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith("Api.class") && !name.contains("$")) {
                    found.add(Class.forName(Http.class.getPackageName() + "."
                            + name.substring(0, name.length() - ".class".length())));
                }
            }
        }
        return found;
    }

    private static Path sourceRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path root = dir.resolve("src/main/java");
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(root)) {
                return root;
            }
        }
        throw new IllegalStateException("no src/main/java above " + Path.of("").toAbsolutePath());
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(sourceRoot())) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
