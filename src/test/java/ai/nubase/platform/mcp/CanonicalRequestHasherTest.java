package ai.nubase.platform.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRequestHasherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalRequestHasher hasher = new CanonicalRequestHasher(objectMapper);

    @Test
    void objectFieldOrderDoesNotChangeTheDigest() {
        ObjectNode first = objectMapper.createObjectNode();
        first.put("ref", "goai_notes");
        first.put("name", "Notes");
        ObjectNode second = objectMapper.createObjectNode();
        second.put("name", "Notes");
        second.put("ref", "goai_notes");

        String firstDigest = hasher.hash(first);
        String secondDigest = hasher.hash(second);

        assertThat(firstDigest)
                .matches("[0-9a-f]{64}")
                .isEqualTo(secondDigest);
    }

    @Test
    void semanticChangesProduceDifferentDigests() {
        ObjectNode original = objectMapper.createObjectNode();
        original.put("ref", "goai_notes");
        original.put("name", "Notes");
        ObjectNode changed = original.deepCopy();
        changed.put("name", "Different Notes");

        assertThat(hasher.hash(original)).isNotEqualTo(hasher.hash(changed));
    }

    @Test
    void arrayOrderRemainsSignificant() {
        ObjectNode first = objectMapper.createObjectNode();
        first.putArray("steps").add("create").add("provision");
        ObjectNode second = objectMapper.createObjectNode();
        second.putArray("steps").add("provision").add("create");

        assertThat(hasher.hash(first)).isNotEqualTo(hasher.hash(second));
    }
}
