package eu.wohlben.qits.cli.bootstrap.phases;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>The pin list's keys, asked rather than restated.</b>
 * {@code PipelinePhases.IMAGE_PINS} and the {@code ImagePin} record are package-private, and
 * {@link eu.wohlben.qits.cli.bootstrap.platform.ExtrasWiringGuardTest} — which has to know that an
 * image-version key is platform wiring rather than a per-service default — sits in another package.
 * A second copy of the names there would be a list that agrees with the pins until the day somebody
 * adds one, which is exactly the failure the guard exists to catch.
 * <p>
 * So this is a bridge and nothing else: one line of test code in the pin list's own package, kept
 * here rather than solved by widening the visibility of production state.
 * <p>
 * <b>The answer is the empty set today</b>, because no image is pinned from configuration any more.
 * That is a fact about the pin list and not about this bridge — asking is still how the guard finds
 * out, and the day a pin is added the guard classifies its key without an edit here.
 */
public final class ImagePinKeys {

    private ImagePinKeys() {
    }

    /** The environment variable each pin writes, across every application that reads one. */
    public static Set<String> keys() {
        return PipelinePhases.IMAGE_PINS.stream()
                .map(PipelinePhases.ImagePin::key)
                .collect(Collectors.toUnmodifiableSet());
    }
}
