package com.telcobright.seed.switchledger;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0077-A item 1, the rule: this module is a module OF the base, in the parent's list; the base (seed-sessionflow) gains no
 * dependency — never mem-ledger, never Chronicle: the ad must not carry them. The poms are read as the build reads them (the working
 * directory of the suite is this module; a clean copy keeps the same layout).
 */
class ModuleShapeTest {

    private static final Path PARENT = Path.of("..", "pom.xml");
    private static final Path BASE = Path.of("..", "seed-sessionflow", "pom.xml");
    private static final Path THIS = Path.of("pom.xml");

    @Test
    void theParentListsThisModule() throws Exception {
        assertThat(texts(PARENT, "module")).contains("seed-switch-ledger");
    }

    @Test
    void theBaseDeclaresNeitherMemLedgerNorChronicle() throws Exception {
        assertThat(texts(BASE, "artifactId")).as("seed-sessionflow's dependencies").doesNotContain("mem-ledger", "chronicle-queue", "seed-switch-ledger");
        assertThat(texts(BASE, "groupId")).doesNotContain("net.openhft");
    }

    @Test
    void thisModuleDependsOnTheBase_theDomain_andTheLedger_onlyThese() throws Exception {
        List<String> artifacts = texts(THIS, "artifactId");
        assertThat(artifacts).contains("seed-sessionflow", "rtc-domain", "mem-ledger");
        assertThat(artifacts).as("the HTTP ledger of the ad is not this module's").doesNotContain("routesphere-core", "jedis", "kafka-clients");
    }

    private static List<String> texts(Path pom, String tag) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Files.newInputStream(pom));
        NodeList nodes = doc.getElementsByTagName(tag);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) out.add(((Element) nodes.item(i)).getTextContent().trim());
        return out;
    }
}
