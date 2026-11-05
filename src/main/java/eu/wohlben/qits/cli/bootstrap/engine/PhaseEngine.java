package eu.wohlben.qits.cli.bootstrap.engine;

import eu.wohlben.qits.cli.bootstrap.ui.Ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Runs an ordered list of phases and reports every transition to the {@link Ui}.
 * <p>
 * The rules are the script's: a phase that throws stops the boot (exit 2); a phase that warns lets
 * the boot continue and makes the exit code 1; a phase that skips says why. Nothing here knows what
 * a phase does — the engine is the part that can be tested without docker.
 */
public class PhaseEngine {

    private final Ui ui;
    private final LongSupplier nanoClock;

    public PhaseEngine(Ui ui) {
        this(ui, System::nanoTime);
    }

    /** The clock is an argument so a test can measure phase timing without sleeping. */
    public PhaseEngine(Ui ui, LongSupplier nanoClock) {
        this.ui = ui;
        this.nanoClock = nanoClock;
    }

    /**
     * Runs {@code plan} in order. A phase may hand back follow-up phases ({@link PhaseContext#then});
     * they are inserted immediately after it, and the displays are told the plan changed — so the
     * header's total grows when they arrive, and is provisional until then.
     */
    public RunResult run(List<Phase> plan) {
        List<Phase> phases = new ArrayList<>(plan);
        ui.started(List.copyOf(phases));
        List<PhaseOutcome> outcomes = new ArrayList<>();
        long runStart = nanoClock.getAsLong();
        int exit = 0;

        for (int i = 0; i < phases.size(); i++) {
            Phase phase = phases.get(i);
            ui.phaseStarted(i, phase);
            long start = nanoClock.getAsLong();
            Recorder recorder = new Recorder(ui);
            PhaseState state;
            String note;
            Throwable error = null;
            try {
                phase.action().run(recorder);
                state = recorder.warning == null ? PhaseState.DONE : PhaseState.WARNED;
                note = recorder.warning != null ? recorder.warning : recorder.note;
                if (!recorder.followUps.isEmpty()) {
                    insert(phases, i + 1, recorder.followUps);
                    ui.planChanged(List.copyOf(phases));
                }
            } catch (PhaseSkipped skipped) {
                state = PhaseState.SKIPPED;
                note = skipped.getMessage();
            } catch (Throwable t) {
                state = PhaseState.FAILED;
                note = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                error = t;
            }
            Duration took = Duration.ofNanos(nanoClock.getAsLong() - start);
            PhaseOutcome outcome = new PhaseOutcome(i, phase, state, took, note, error);
            outcomes.add(outcome);
            ui.phaseFinished(outcome);

            if (state == PhaseState.FAILED) {
                exit = 2;
                break;
            }
            if (state == PhaseState.WARNED) {
                exit = Math.max(exit, 1);
            }
        }

        RunResult result = new RunResult(exit, List.copyOf(outcomes),
                Duration.ofNanos(nanoClock.getAsLong() - runStart));
        ui.finished(result);
        return result;
    }

    /** Follow-ups go in at {@code at}; an id the plan already holds fails the phase that asked. */
    private static void insert(List<Phase> phases, int at, List<Phase> followUps) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        phases.forEach(phase -> ids.add(phase.id()));
        for (Phase followUp : followUps) {
            if (!ids.add(followUp.id())) {
                throw new IllegalStateException("phase id " + followUp.id()
                        + " is already in the plan; a follow-up phase needs an id of its own");
            }
        }
        phases.addAll(at, followUps);
    }

    /** The context handed to a phase: everything it says goes straight to the display. */
    private static final class Recorder implements PhaseContext {
        private final Ui ui;
        private String note = "";
        private String warning;
        private final List<Phase> followUps = new ArrayList<>();

        @Override
        public void then(List<Phase> phases) {
            followUps.addAll(phases);
        }

        private Recorder(Ui ui) {
            this.ui = ui;
        }

        @Override
        public void log(String line) {
            ui.output(line);
        }

        @Override
        public void status(String status) {
            ui.status(status);
        }

        @Override
        public void note(String note) {
            this.note = note;
        }

        @Override
        public void warn(String message) {
            // First warning wins on the header line; every one of them is in the body and the log.
            if (warning == null) {
                warning = message;
            }
            ui.output("!! " + message);
        }
    }
}
