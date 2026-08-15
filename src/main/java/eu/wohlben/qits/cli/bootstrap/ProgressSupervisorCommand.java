package eu.wohlben.qits.cli.bootstrap;

import eu.wohlben.qits.cli.bootstrap.ui.BootState;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Keeps the public progress surface alive independently of a bootstrap worker. */
@CommandLine.Command(name = "progress-supervisor", hidden = true)
public class ProgressSupervisorCommand implements Callable<Integer> {
    @CommandLine.Option(names = "--state", required = true)
    Path state;

    @Override
    public Integer call() throws Exception {
        BootState published = new BootState(2000);
        BootState.publish(published);
        while (!Thread.currentThread().isInterrupted()) {
            if (Files.isRegularFile(state)) {
                published.replaceSnapshot(Files.readString(state, StandardCharsets.UTF_8));
            }
            Thread.sleep(100);
        }
        return 0;
    }
}
