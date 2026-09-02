package com.java.system.sessionagent;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandaloneProjectTest {

    private static final String FORBIDDEN_TRANSPORT_TOKEN = "sla" + "ck";
    private static final String RESERVED_TRANSPORT_CONTRACT_FILE = "src/test/shell/docker-contract-test.sh";

    @Test
    void isAnIndependentBuildProjectWithLockedTooling() throws Exception {
        Path projectPom = Path.of("pom.xml").toRealPath();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(projectPom.toFile());
        Element project = document.getDocumentElement();

        assertEquals("session-agent-runtime", childText(project, "artifactId"));
        assertEquals("com.java.system.sessionagent", childText(project, "groupId"));
        assertFalse(hasChild(project, "modules"));
        assertTrue(hasChild(project, "parent"));

        Element parent = child(project, "parent");
        assertEquals("org.springframework.boot", childText(parent, "groupId"));
        assertEquals("spring-boot-starter-parent", childText(parent, "artifactId"));
        assertEquals("4.1.0", childText(parent, "version"));
        assertEquals("", childText(parent, "relativePath"));

        assertLockedBuildConfiguration(project);
        assertNoForbiddenDependencies(project);
        assertNoForbiddenTransportReferenceInProjectFiles(projectPom);
    }

    @Test
    void permitsOnlyTheReservedTransportEnvironmentContractFile() {
        assertTrue(isReservedTransportContractFile(RESERVED_TRANSPORT_CONTRACT_FILE));
        assertFalse(isReservedTransportContractFile("src/test/shell/repository-contract-test.sh"));
        assertFalse(isReservedTransportContractFile("src/main/java/com/java/system/sessionagent/SessionAgentRuntimeApplication.java"));
    }

    private static void assertLockedBuildConfiguration(Element project) {
        Element properties = child(project, "properties");
        assertEquals("21", childText(properties, "java.version"));
        assertEquals("2.0.1", childText(properties, "spring-ai.version"));
        assertEquals("2.1.0", childText(properties, "spring-modulith.version"));
        assertEquals("1.4.2", childText(properties, "archunit.version"));

        Element managedDependencies = child(child(project, "dependencyManagement"), "dependencies");
        assertImportedBom(managedDependencies, "org.springframework.ai", "spring-ai-bom", "${spring-ai.version}");
        assertImportedBom(managedDependencies, "org.springframework.modulith", "spring-modulith-bom", "${spring-modulith.version}");
        assertDeclaredDependency(child(project, "dependencies"), "org.springframework.ai", "spring-ai-starter-model-google-genai");

        Element build = child(project, "build");
        Element surefire = pluginByArtifactId(build, "maven-surefire-plugin");
        assertEquals(Set.of("**/*Test.java"), elementText(child(child(surefire, "configuration"), "includes"), "include"));

        Element profile = profileById(child(project, "profiles"), "postgres-it");
        Element failsafe = pluginByArtifactId(child(profile, "build"), "maven-failsafe-plugin");
        assertEquals(Set.of("**/*PostgresIT.java"), elementText(child(child(failsafe, "configuration"), "includes"), "include"));
        assertEquals(Set.of("integration-test", "verify"), elementText(child(failsafe, "executions"), "goal"));

        Element liveProfile = profileById(child(project, "profiles"), "live-it");
        Element liveSurefire = pluginByArtifactId(child(liveProfile, "build"), "maven-surefire-plugin");
        Element liveIncludes = child(child(liveSurefire, "configuration"), "includes");
        assertEquals("override", liveIncludes.getAttribute("combine.self"));
        assertEquals(Set.of("**/SessionAgentLiveIT.java"), elementText(liveIncludes, "include"));
    }

    private static void assertNoForbiddenDependencies(Element project) {
        NodeList dependencies = project.getElementsByTagNameNS("*", "dependency");
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String groupId = childText(dependency, "groupId");
            String artifactId = childText(dependency, "artifactId");
            String coordinate = groupId + ":" + artifactId;

            assertFalse(containsForbiddenTransportReference(coordinate),
                    () -> "Standalone project must not declare forbidden transport dependency " + coordinate);
        }
    }

    private static void assertNoForbiddenTransportReferenceInProjectFiles(Path projectPom) throws Exception {
        Path projectRoot = projectPom.getParent();
        Path sourceRoot = projectRoot.resolve("src");
        List<Path> sourceFiles;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            sourceFiles = paths.filter(Files::isRegularFile).toList();
        }

        List<Path> projectFiles = Stream.concat(sourceFiles.stream(), Stream.of(projectPom)).toList();
        for (Path projectFile : projectFiles) {
            String relativePath = projectRoot.relativize(projectFile).toString().replace(File.separatorChar, '/');
            String content = Files.readString(projectFile);

            if (isReservedTransportContractFile(relativePath)) {
                continue;
            }

            assertFalse(containsForbiddenTransportReference(relativePath),
                    () -> "Standalone project must not contain forbidden transport resource " + relativePath);
            assertFalse(containsForbiddenTransportReference(content),
                    () -> "Standalone project must not reference forbidden transport in " + relativePath);
        }
    }

    private static boolean isReservedTransportContractFile(String relativePath) {
        return RESERVED_TRANSPORT_CONTRACT_FILE.equals(relativePath);
    }

    private static void assertImportedBom(Element dependencies, String groupId, String artifactId, String version) {
        Element dependency = dependencyByCoordinate(dependencies, groupId, artifactId);
        assertEquals(version, childText(dependency, "version"));
        assertEquals("pom", childText(dependency, "type"));
        assertEquals("import", childText(dependency, "scope"));
    }

    private static void assertDeclaredDependency(Element dependencies, String groupId, String artifactId) {
        dependencyByCoordinate(dependencies, groupId, artifactId);
    }

    private static Element dependencyByCoordinate(Element dependencies, String groupId, String artifactId) {
        NodeList dependencyNodes = dependencies.getElementsByTagNameNS("*", "dependency");
        for (int index = 0; index < dependencyNodes.getLength(); index++) {
            Element dependency = (Element) dependencyNodes.item(index);
            if (groupId.equals(childText(dependency, "groupId")) && artifactId.equals(childText(dependency, "artifactId"))) {
                return dependency;
            }
        }
        throw new AssertionError("Missing " + groupId + ":" + artifactId + " dependency");
    }

    private static boolean containsForbiddenTransportReference(String candidate) {
        String normalizedCandidate = candidate.toLowerCase(Locale.ROOT);
        int index = normalizedCandidate.indexOf(FORBIDDEN_TRANSPORT_TOKEN);
        while (index >= 0) {
            if (isForbiddenTransportStart(candidate, index) && isForbiddenTransportEnd(candidate, index)) {
                return true;
            }
            index = normalizedCandidate.indexOf(FORBIDDEN_TRANSPORT_TOKEN, index + 1);
        }
        return false;
    }

    private static boolean isForbiddenTransportStart(String candidate, int index) {
        return index == 0 || !Character.isLetterOrDigit(candidate.charAt(index - 1));
    }

    private static boolean isForbiddenTransportEnd(String candidate, int index) {
        int tokenEnd = index + FORBIDDEN_TRANSPORT_TOKEN.length();
        return tokenEnd == candidate.length()
                || !Character.isLetterOrDigit(candidate.charAt(tokenEnd))
                || Character.isUpperCase(candidate.charAt(tokenEnd));
    }

    private static Element child(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        throw new AssertionError("Missing " + localName + " element");
    }

    private static String childText(Element parent, String localName) {
        return child(parent, localName).getTextContent().trim();
    }

    private static boolean hasChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> elementText(Element parent, String localName) {
        NodeList descendants = parent.getElementsByTagNameNS("*", localName);
        HashSet<String> values = new HashSet<>();
        for (int index = 0; index < descendants.getLength(); index++) {
            values.add(descendants.item(index).getTextContent().trim());
        }
        return Set.copyOf(values);
    }

    private static Element pluginByArtifactId(Element build, String artifactId) {
        NodeList plugins = build.getElementsByTagNameNS("*", "plugin");
        for (int index = 0; index < plugins.getLength(); index++) {
            Element plugin = (Element) plugins.item(index);
            if (artifactId.equals(childText(plugin, "artifactId"))) {
                return plugin;
            }
        }
        throw new AssertionError("Missing " + artifactId + " plugin");
    }

    private static Element profileById(Element profiles, String profileId) {
        NodeList profileNodes = profiles.getElementsByTagNameNS("*", "profile");
        for (int index = 0; index < profileNodes.getLength(); index++) {
            Element profile = (Element) profileNodes.item(index);
            if (profileId.equals(childText(profile, "id"))) {
                return profile;
            }
        }
        throw new AssertionError("Missing " + profileId + " profile");
    }
}
