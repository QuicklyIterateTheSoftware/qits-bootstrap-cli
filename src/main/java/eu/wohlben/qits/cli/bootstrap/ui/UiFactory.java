package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintStream;

/**
 * Picks the display. A real terminal gets the live one; a pipe, a dumb TERM or
 * {@code QITS_TUI=0} gets plain lines, which is what a CI job wants anyway.
 * <p>
 * Whichever it is, the browser view is fed beside it unless {@code QITS_WEB=0} — one run, shown in
 * two places.
 * <p>
 * JLine is asked for its {@code exec} provider by name. Left to itself it prefers the providers
 * that call libc — through JNI or the foreign function API — and neither survives being compiled
 * into a native image. {@code exec} shells {@code /bin/stty}, which costs one process per terminal
 * and behaves the same in both build forms.
 */
public final class UiFactory {

    private UiFactory() {
    }

    public static Ui create(BootstrapConfig config, PrintStream out) {
        if (!config.web()) {
            return terminal(config, out);
        }
        // Said before the live display takes the screen, so it stays in the scrollback above it.
        out.println("browser view: http://" + url(config.webHost()) + ":" + config.webPort());
        return new CompositeUi(terminal(config, out), new WebUi(config));
    }

    /** 0.0.0.0 is what the server binds, not an address to type into a browser. */
    private static String url(String host) {
        return host == null || host.isBlank() || "0.0.0.0".equals(host) ? "127.0.0.1" : host;
    }

    private static Ui terminal(BootstrapConfig config, PrintStream out) {
        if (!config.tui() || !isTerminal()) {
            return new PlainUi(out);
        }
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .provider("exec")
                    .system(true)
                    .dumb(false)
                    .build();
            if (terminal.getHeight() < 12 || terminal.getWidth() < 40) {
                terminal.close();
                return new PlainUi(out);
            }
            return new TuiUi(terminal, config.tailLines(), config.logFile());
        } catch (Exception e) {
            // No terminal to be had. Say so once and carry on in plain lines.
            out.println("no interactive terminal (" + e.getMessage() + ") — plain output");
            return new PlainUi(out);
        }
    }

    private static boolean isTerminal() {
        String term = System.getenv("TERM");
        if ("dumb".equals(term)) {
            return false;
        }
        var console = System.console();
        return console != null && console.isTerminal();
    }
}
