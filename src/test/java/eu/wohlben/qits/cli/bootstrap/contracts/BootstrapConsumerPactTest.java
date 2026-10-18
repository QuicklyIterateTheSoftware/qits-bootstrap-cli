package eu.wohlben.qits.cli.bootstrap.contracts;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>The consumer half of every contract the bootstrap has</b>: for each row of
 * {@link BootstrapContract}, the bootstrap's real API class over the real {@code Http}, pointed at
 * a pact-jvm mock server that answers exactly what the row's interaction promises — and the row's
 * own assertions on what the bootstrap made of it. A row whose request the code does not make, or
 * whose answer it cannot read, fails here.
 * <p>
 * <b>A pending row is SKIPPED, with the provider state it waits for as the reason</b> — and, where
 * the provider recorded the operation with another request, the difference. It is not in the pact
 * file either: a contract with no recorded answer behind it would be one the provider cannot
 * verify.
 * <p>
 * One mock server per row, as qits-maintenance-service's copy does: several rows send the same
 * request and differ only in their trigger, which one server serving a whole pact could not tell
 * apart. Plain JUnit; nothing here boots Quarkus.
 */
class BootstrapConsumerPactTest {

    static {
        // pact-jvm reports usage metrics over the network unless told not to; a test never should.
        System.setProperty("pact_do_not_track", "true");
    }

    @TestFactory
    Stream<DynamicTest> everyRowIsWhatTheBootstrapAsksAndUnderstands() {
        return BootstrapContract.CASES.stream().map(row -> DynamicTest.dynamicTest(
                row.provider().repository() + " | " + row.description() + " [" + row.state() + "]",
                () -> {
                    Assumptions.assumeTrue(row.recorded(), row::pendingReason);
                    GoldenMasters recordings = row.provider().recordings();
                    GoldenMasters.Operation op = recordings.operation(row.state(),
                            row.operationId());
                    JsonNode recorded = row.reads().isEmpty() ? MissingNode.getInstance()
                            : recordings.json(row.state(), row.operationId());
                    PactVerificationResult result = ConsumerPactRunnerKt.runConsumerTest(
                            BootstrapContract.pact(row.provider(), List.of(row)),
                            MockProviderConfig.createDefault(PactSpecVersion.V4),
                            (mockServer, context) -> {
                                row.call().run(mockServer.getUrl(),
                                        recordings.params(row.state()), recorded, op);
                                return null;
                            });
                    if (!(result instanceof PactVerificationResult.Ok)) {
                        fail(describe(result));
                    }
                }));
    }

    private static String describe(PactVerificationResult result) {
        if (result instanceof PactVerificationResult.Error error) {
            return "error: " + error.getError();
        }
        return result.getDescription() + " — " + result;
    }
}
