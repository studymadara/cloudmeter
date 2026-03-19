package io.cloudmeter.costengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScalePointTest {

    @Test
    void constructor_setsFields() {
        ScalePoint sp = new ScalePoint(1_000, 42.50);
        assertEquals(1_000, sp.getConcurrentUsers());
        assertEquals(42.50, sp.getMonthlyCostUsd(), 1e-9);
    }

    @Test
    void zeroCost_isAllowed() {
        assertDoesNotThrow(() -> new ScalePoint(100, 0.0));
    }

    @Test
    void zeroUsers_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ScalePoint(0, 10.0));
    }

    @Test
    void negativeUsers_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ScalePoint(-1, 10.0));
    }

    @Test
    void negativeCost_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ScalePoint(100, -0.01));
    }

    @Test
    void toString_containsUsersAndCost() {
        String s = new ScalePoint(5_000, 123.45).toString();
        assertTrue(s.contains("5000"));
        assertTrue(s.contains("123.45"));
    }
}
