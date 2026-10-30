package eu.wohlben.qits.cli.bootstrap.platform;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reactor's poms pin, read the way Maven would resolve it — the list a seed image build
 * asks the file repository for (qits-889). The poms here are cut to the shapes this platform's
 * own poms are written in: properties in the root, management in the root, the pins in a module.
 */
class MavenPinsTest {

    private static final String ROOT = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>eu.wohlben</groupId>
              <artifactId>qits</artifactId>
              <version>2026.1030.124126</version>
              <modules><module>ci</module><module>service</module></modules>
              <properties>
                <qits.eventstream.version>2026.1027.101950</qits.eventstream.version>
                <qits.idp-golden-masters.version>2026.1027.165632</qits.idp-golden-masters.version>
                <qits.ci-frontend-pacts-qits-ci-service.version>2026.1021.191659</qits.ci-frontend-pacts-qits-ci-service.version>
                <!-- <qits.commented-golden-masters.version>1</qits.commented-golden-masters.version> -->
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>${project.groupId}.qits</groupId>
                    <artifactId>qits-eventstream</artifactId>
                    <version>${qits.eventstream.version}</version>
                  </dependency>
                  <dependency>
                    <groupId>eu.wohlben.qits</groupId>
                    <artifactId>qits-idp-golden-masters</artifactId>
                    <version>${qits.idp-golden-masters.version}</version>
                    <scope>test</scope>
                  </dependency>
                  <dependency>
                    <groupId>eu.wohlben.qits</groupId>
                    <artifactId>qits-unused-golden-masters</artifactId>
                    <version>2026.1001.1</version>
                  </dependency>
                  <dependency>
                    <groupId>eu.wohlben.qits</groupId>
                    <artifactId>qits-bom</artifactId>
                    <version>2026.1001.2</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-bom</artifactId>
                    <version>3.30.0</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    private static final String CI = """
            <project>
              <parent>
                <groupId>eu.wohlben</groupId>
                <artifactId>qits</artifactId>
                <version>2026.1030.124126</version>
              </parent>
              <groupId>eu.wohlben.qits</groupId>
              <artifactId>qits-ci</artifactId>
              <properties>
                <local.version>7</local.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>${project.groupId}</groupId>
                  <artifactId>qits-eventstream</artifactId>
                </dependency>
                <dependency>
                  <groupId>eu.wohlben.qits</groupId>
                  <artifactId>qits-local</artifactId>
                  <version>${local.version}</version>
                  <classifier>runner</classifier>
                  <exclusions>
                    <exclusion>
                      <groupId>eu.wohlben.qits</groupId>
                      <artifactId>qits-excluded</artifactId>
                    </exclusion>
                  </exclusions>
                </dependency>
              </dependencies>
            </project>
            """;

    private static final String SERVICE = """
            <project>
              <parent>
                <groupId>eu.wohlben</groupId>
                <artifactId>qits</artifactId>
                <version>2026.1030.124126</version>
              </parent>
              <groupId>eu.wohlben.qits</groupId>
              <artifactId>qits-ci-service</artifactId>
              <dependencies>
                <dependency>
                  <groupId>eu.wohlben.qits</groupId>
                  <artifactId>qits-ci</artifactId>
                  <version>${project.version}</version>
                </dependency>
                <dependency>
                  <groupId>eu.wohlben.qits</groupId>
                  <artifactId>qits-idp-golden-masters</artifactId>
                </dependency>
                <dependency>
                  <groupId>eu.wohlben.qits</groupId>
                  <artifactId>qits-ci-frontend-pacts-qits-ci-service</artifactId>
                  <version>${qits.ci-frontend-pacts-qits-ci-service.version}</version>
                  <scope>test</scope>
                </dependency>
                <dependency>
                  <groupId>eu.wohlben.qits</groupId>
                  <artifactId>qits-nobody-knows</artifactId>
                  <version>${qits.nobody.version}</version>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

    private static MavenPins.Pins reactor() {
        Map<String, String> poms = new LinkedHashMap<>();
        poms.put("pom.xml", ROOT);
        poms.put("ci/pom.xml", CI);
        poms.put("service/pom.xml", SERVICE);
        return MavenPins.read(poms);
    }

    /**
     * <b>Every qits coordinate, every scope, its version resolved</b> — through the module's own
     * properties, then the root's, and through the root's management where a module names no
     * version; {@code ${project.groupId}} is the pom's own group. The reactor's own modules are no
     * pins, an exclusion is no dependency, and a third-party coordinate is not this reader's.
     */
    @Test
    void everyQitsCoordinateIsReadWithItsVersionResolved() {
        MavenPins.Pins pins = reactor();

        assertThat(pins.dependencies()).extracting(MavenPins.Coordinate::gav).containsExactly(
                "qits-eventstream:2026.1027.101950",
                "qits-local:7",
                "qits-idp-golden-masters:2026.1027.165632",
                "qits-ci-frontend-pacts-qits-ci-service:2026.1021.191659",
                // The import-scoped BOM is resolved whether a module names it or not.
                "qits-bom:2026.1001.2");
        assertThat(pins.dependencies()).extracting(MavenPins.Coordinate::artifactId)
                .doesNotContain("qits-ci", "qits-excluded", "junit-jupiter", "quarkus-bom",
                        "qits-unused-golden-masters");
        // The scope a module leaves out is the managed entry's.
        assertThat(pins.dependencies()).filteredOn(c -> c.artifactId().equals("qits-idp-golden-masters"))
                .singleElement().satisfies(c -> {
                    assertThat(c.scope()).isEqualTo("test");
                    assertThat(c.pom()).isEqualTo("service/pom.xml");
                });
        // What nothing resolves is said, not guessed.
        assertThat(pins.unresolved()).containsExactly("qits-nobody-knows (service/pom.xml)");
    }

    /** Where a Maven repository holds each: a classifier in the file name, a BOM as its pom. */
    @Test
    void aCoordinateKnowsItsPathInTheRepository() {
        MavenPins.Pins pins = reactor();

        assertThat(pins.dependencies()).extracting(MavenPins.Coordinate::repositoryPath).contains(
                "eu/wohlben/qits/qits-eventstream/2026.1027.101950/qits-eventstream-2026.1027.101950.jar",
                "eu/wohlben/qits/qits-local/7/qits-local-7-runner.jar",
                "eu/wohlben/qits/qits-bom/2026.1001.2/qits-bom-2026.1001.2.pom");
    }

    /**
     * <b>The contract pins, both ways</b>: the {@code qits.<key>.version} properties of a pom text
     * — how a root pom at a tag is read — and the coordinates of a reactor, managed ones included.
     * A comment is no pin, and neither is a version nothing can pack.
     */
    @Test
    void contractPinsAreTheGoldenMastersAndThePactsAnyPomNames() {
        Map<String, Set<String>> pins = MavenPins.contractPins(Arrays.asList(
                ROOT, null,
                "<qits.idp-golden-masters.version>2026.1021.194347</qits.idp-golden-masters.version>"
                        + "<qits.ci-golden-masters.version>${revision}</qits.ci-golden-masters.version>"
                        + "<qits.eventstream.version>2026.1027.101950</qits.eventstream.version>"
                        + "<qits.bootstrap-cli-pacts-qits-ci-service.version>\n 2026.1023.225504\n"
                        + "</qits.bootstrap-cli-pacts-qits-ci-service.version>"),
                List.of(reactor()));

        assertThat(pins).containsOnlyKeys("qits-idp-golden-masters",
                "qits-ci-frontend-pacts-qits-ci-service", "qits-bootstrap-cli-pacts-qits-ci-service",
                "qits-unused-golden-masters");
        assertThat(pins.get("qits-idp-golden-masters"))
                .containsExactly("2026.1027.165632", "2026.1021.194347");
        assertThat(pins.get("qits-bootstrap-cli-pacts-qits-ci-service"))
                .containsExactly("2026.1023.225504");
    }

    /** The two coordinate grammars of {@code CiContracts.coordinate}, read back. */
    @Test
    void aContractCoordinateNamesItsSource() {
        assertThat(MavenPins.goldenMastersApp("qits-projects-daemon-golden-masters"))
                .isEqualTo("projects-daemon");
        assertThat(MavenPins.goldenMastersApp("qits-eventstream")).isNull();
        assertThat(MavenPins.pactParties("qits-ci-frontend-pacts-qits-edge-service"))
                .containsExactly("qits-ci-frontend", "qits-edge-service");
        assertThat(MavenPins.pactParties("qits-maintenance-service-pacts-qits-mirror-service"))
                .containsExactly("qits-maintenance-service", "qits-mirror-service");
        assertThat(MavenPins.pactParties("qits-idp-golden-masters")).isNull();
        assertThat(MavenPins.pactParties("qits-x-pacts-")).isNull();
    }
}
