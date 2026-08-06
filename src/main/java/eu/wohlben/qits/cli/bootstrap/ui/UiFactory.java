package eu.wohlben.qits.cli.bootstrap.ui;

import eu.wohlben.qits.cli.bootstrap.config.BootstrapConfig;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintStream;

/**
 * Picks the display. A real terminal gets the live one; a pipe, a dumb TERM or
 * {@code QITS_TUI=0} gets plain lines, which is what a CI job wants anyway.
 */
public final class UiFactory {

    private UiFactory() {
    }

    public static Ui create(BootstrapConfig config, PrintStream out) {
        if (!config.tui() || !isTerminal()) {
            return new PlainUi(out);
        }
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).dumb(false).build();
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
