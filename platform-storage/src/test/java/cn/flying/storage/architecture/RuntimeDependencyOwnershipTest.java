package cn.flying.storage.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards production dependency ownership that cannot be delegated to unrelated starter transitives.
 */
class RuntimeDependencyOwnershipTest {

    private static final String JACKSON_GROUP = "com.fasterxml.jackson.core";
    private static final Set<String> REQUIRED_JACKSON_ARTIFACTS = Set.of(
            "jackson-annotations",
            "jackson-core",
            "jackson-databind"
    );

    /**
     * Verifies every Jackson 2 module imported by storage production code is direct and BOM-managed.
     */
    @Test
    void shouldDeclareJackson2ProductionDependenciesDirectly() throws Exception {
        Map<String, DependencyDeclaration> directJacksonDependencies = readDirectJacksonDependencies(locateStoragePom());

        assertEquals(REQUIRED_JACKSON_ARTIFACTS, directJacksonDependencies.keySet(),
                "Storage must own every Jackson 2 module imported by production code");
        directJacksonDependencies.forEach((artifactId, declaration) -> {
            assertTrue(declaration.scope().isBlank() || "compile".equals(declaration.scope()),
                    () -> artifactId + " must be a production dependency");
            assertTrue(declaration.version().isBlank(),
                    () -> artifactId + " must inherit its version from the managed Jackson BOM");
        });
    }

    /**
     * Locates the storage POM when Maven is launched from either the repository root or module root.
     */
    private Path locateStoragePom() {
        List<Path> candidates = List.of(Path.of("platform-storage", "pom.xml"), Path.of("pom.xml"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && isStoragePom(candidate)) {
                return candidate;
            }
        }
        return fail("Unable to locate platform-storage/pom.xml from " + Path.of("").toAbsolutePath());
    }

    /**
     * Confirms that a candidate POM belongs to the storage module.
     */
    private boolean isStoragePom(Path candidate) {
        try {
            return "platform-storage".equals(directChildText(parsePom(candidate).getDocumentElement(), "artifactId"));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Reads Jackson declarations only from project/dependencies, excluding dependencyManagement and transitives.
     */
    private Map<String, DependencyDeclaration> readDirectJacksonDependencies(Path pomPath) throws Exception {
        Element project = parsePom(pomPath).getDocumentElement();
        Element dependencies = directChild(project, "dependencies");
        assertNotNull(dependencies, "Storage POM must declare project dependencies");

        Map<String, DependencyDeclaration> declarations = new LinkedHashMap<>();
        for (Element dependency : directChildren(dependencies, "dependency")) {
            if (!JACKSON_GROUP.equals(directChildText(dependency, "groupId"))) {
                continue;
            }
            String artifactId = directChildText(dependency, "artifactId");
            DependencyDeclaration previous = declarations.put(artifactId, new DependencyDeclaration(
                    directChildText(dependency, "scope"),
                    directChildText(dependency, "version")
            ));
            assertTrue(previous == null, () -> "Duplicate direct dependency: " + JACKSON_GROUP + ":" + artifactId);
        }
        return declarations;
    }

    /**
     * Parses a Maven POM with external entity expansion disabled.
     */
    private Document parsePom(Path pomPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(pomPath.toFile());
    }

    /**
     * Returns a named direct child element without traversing nested Maven sections.
     */
    private Element directChild(Element parent, String name) {
        for (Element child : directChildren(parent, name)) {
            return child;
        }
        return null;
    }

    /**
     * Returns all direct child elements with the requested local node name.
     */
    private List<Element> directChildren(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        List<Element> matches = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element && name.equals(element.getNodeName())) {
                matches.add(element);
            }
        }
        return matches;
    }

    /**
     * Returns trimmed text from a named direct child, or an empty string when absent.
     */
    private String directChildText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    /**
     * Captures the ownership-relevant fields of one direct dependency declaration.
     */
    private record DependencyDeclaration(String scope, String version) {
    }
}
