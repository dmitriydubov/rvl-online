package ru.projectx.contracts.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class HelmStandValuesTest {

    private static final Path VALUES = Path.of("devops", "test", "helm", "values.yaml");

    @Test
    void ingressResources_includeRequestsContractsPoliciesAndAttachments() throws IOException {
        Map<?, ?> root = new Yaml().loadAs(Files.readString(VALUES, StandardCharsets.UTF_8), Map.class);
        assertThat(root.get("ingress")).isInstanceOfSatisfying(Map.class, ingress ->
                assertThat(ingress.get("resources")).isEqualTo(List.of(
                        "internal/v1/requests",
                        "internal/v1/contracts",
                        "internal/v1/policies",
                        "internal/v1/attachments")));
    }
}
