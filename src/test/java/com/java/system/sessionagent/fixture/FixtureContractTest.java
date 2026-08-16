package com.java.system.sessionagent.fixture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureContractTest {

    private static final Path FIXTURES_ROOT = Path.of("fixtures");

    @Test
    void paymentFixtureKeepsRuntimeDefinedFeesAndARestrictedPaymentSurface() throws Exception {
        Path paymentFixture = FIXTURES_ROOT.resolve("payment-service");

        assertThat(paymentFixture).isDirectory();
        assertStandaloneFixtureBuild(paymentFixture);
        assertThat(productionDependencyArtifactIds(paymentFixture.resolve("pom.xml"))).containsExactly("spring-web");
        assertThat(testDependencyArtifactIds(paymentFixture.resolve("pom.xml"))).containsExactlyInAnyOrder("junit-jupiter", "assertj-core");

        String paymentMethod = read(paymentFixture, "PaymentMethod.java");
        assertThat(enumValues(paymentMethod)).containsExactly("CREDIT_CARD", "BANK_TRANSFER", "WALLET");

        String settings = read(paymentFixture, "PaymentFeeSettings.java");
        assertThat(settings).contains("Optional<String>", "loadFeeFormulaJson(PaymentMethod");

        String calculator = read(paymentFixture, "PaymentFeeCalculator.java");
        assertThat(calculator)
                .contains("settings.loadFeeFormulaJson(paymentMethod)", "orElseThrow", "FeeFormulaUnavailableException",
                        "feeFormulaEvaluator.evaluate(formulaJson, amount)")
                .doesNotContain("new BigDecimal", "BigDecimal.valueOf", "\"{", "%", "BNPL");

        String controller = read(paymentFixture, "PaymentQueryController.java");
        assertThat(controller).contains("@GetMapping", "PaymentMethod.values()");
        assertThat(javaSources(paymentFixture)).allSatisfy(source -> assertThat(source).doesNotContain("BNPL"));
    }

    @Test
    void orderFixtureKeepsCancellationAsAPersistedOrderOnlyOperation() throws Exception {
        Path orderFixture = FIXTURES_ROOT.resolve("order-service");

        assertThat(orderFixture).isDirectory();
        assertStandaloneFixtureBuild(orderFixture);
        assertThat(productionDependencyArtifactIds(orderFixture.resolve("pom.xml"))).containsExactly("spring-web");
        assertThat(testDependencyArtifactIds(orderFixture.resolve("pom.xml"))).containsExactlyInAnyOrder("junit-jupiter", "assertj-core");
        assertThat(javaSources(orderFixture)).hasSizeLessThan(15);

        String controller = read(orderFixture, "OrderQueryController.java");
        assertThat(controller).contains("@GetMapping", "@PostMapping", "orderService.findOrder", "orderService.cancel");

        String service = read(orderFixture, "OrderService.java");
        assertThat(service).contains("orderRepository.findById(orderId)", "orderRepository.save(cancelledOrder)");

        String order = read(orderFixture, "Order.java");
        String status = read(orderFixture, "OrderStatus.java");
        assertThat(order).contains("OrderStatus.CANCELLED");
        assertThat(status).contains("CANCELLED");
        assertThat(javaSources(orderFixture)).allSatisfy(source ->
                assertThat(source.toLowerCase(Locale.ROOT)).doesNotContain("refund"));
    }

    private static void assertStandaloneFixtureBuild(Path fixture) throws Exception {
        String pom = Files.readString(fixture.resolve("pom.xml"));

        assertThat(pom).contains("<maven.compiler.release>21</maven.compiler.release>");
        assertThat(pom).doesNotContain("spring-boot", "spring-jdbc", "httpclient", "database");
        assertThat(javaSources(fixture)).allSatisfy(source ->
                assertThat(source).doesNotContain("SpringApplication", "@SpringBootApplication", "public static void main"));
    }

    private static String read(Path fixture, String fileName) throws IOException {
        Path source = fixture.resolve("src/main/java/com/example").resolve(fileName.contains("Payment") || fileName.contains("Fee")
                ? "payment" : "order").resolve(fileName);
        assertThat(source).isRegularFile();
        return Files.readString(source);
    }

    private static List<String> javaSources(Path fixture) throws IOException {
        try (Stream<Path> paths = Files.walk(fixture.resolve("src/main/java"))) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .map(FixtureContractTest::readUnchecked)
                    .toList();
        }
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read fixture source", exception);
        }
    }

    private static List<String> productionDependencyArtifactIds(Path pom) throws Exception {
        return dependencyArtifactIds(pom, "");
    }

    private static List<String> testDependencyArtifactIds(Path pom) throws Exception {
        return dependencyArtifactIds(pom, "test");
    }

    private static List<String> dependencyArtifactIds(Path pom, String expectedScope) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        NodeList dependencies = document.getElementsByTagName("dependency");
        List<String> artifactIds = new ArrayList<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String scope = childText(dependency, "scope");
            if (scope.equals(expectedScope)) {
                artifactIds.add(childText(dependency, "artifactId"));
            }
        }
        return artifactIds;
    }

    private static String childText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        return children.getLength() == 0 ? "" : children.item(0).getTextContent().trim();
    }

    private static List<String> enumValues(String source) {
        int bodyStart = source.indexOf('{') + 1;
        int bodyEnd = source.lastIndexOf('}');
        return Stream.of(source.substring(bodyStart, bodyEnd).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
