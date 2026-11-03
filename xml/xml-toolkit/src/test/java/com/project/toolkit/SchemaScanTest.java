package com.project.toolkit;

import com.project.toolkit.generate.ScanCommand;
import com.project.toolkit.generate.SchemaScanResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SchemaScanTest {

    private static final Path SAMPLE_XSD = Path.of("..", "xsd", "SR_2025_Corporate_Action_CAPA_Extension_supl_009_001_11.xsd");

    @Test
    void reportsCountsAndGatesErrorTypesByWhatTheSchemaActuallyHas() {
        SchemaScanResult result = ScanCommand.run(SAMPLE_XSD);

        assertTrue(result.requiredCount > 0);
        assertTrue(result.enumerationCount > 0);
        assertTrue(result.possibleLevels.containsAll(java.util.List.of(1, 2, 3)));

        // This XSD has enumerations and patterns, so those error types should be offered...
        assertTrue(result.possibleErrorTypes.contains("invalid-code"));
        assertTrue(result.possibleErrorTypes.contains("pattern-break"));
        // ...but every repeating element in it uses maxOccurs="unbounded" (finiteRepeatCount==0),
        // so "too-many-repeats" -- which needs a finite cap to exceed -- must not be offered.
        assertEquals(0, result.finiteRepeatCount);
        assertFalse(result.possibleErrorTypes.contains("too-many-repeats"));
        // Always offered regardless of schema content:
        assertTrue(result.possibleErrorTypes.containsAll(java.util.List.of("malformed-xml", "invalid-characters", "wrong-datatype")));
    }
}
