package io.cloudmeter.costengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstanceTypeTest {

    @Test
    void constructor_setsAllFields() {
        InstanceType inst = new InstanceType("m5.large", CloudProvider.AWS, 2.0, 8.0, 0.096);
        assertEquals("m5.large",      inst.getName());
        assertEquals(CloudProvider.AWS, inst.getProvider());
        assertEquals(2.0,              inst.getVcpu(),      1e-9);
        assertEquals(8.0,              inst.getMemoryGib(), 1e-9);
        assertEquals(0.096,            inst.getHourlyUsd(), 1e-9);
    }

    @Test
    void getMonthlyUsd_is730xHourly() {
        InstanceType inst = new InstanceType("t3.micro", CloudProvider.AWS, 2, 1, 0.0104);
        assertEquals(0.0104 * 730, inst.getMonthlyUsd(), 1e-6);
    }

    @Test
    void nullName_throws() {
        assertThrows(NullPointerException.class,
                () -> new InstanceType(null, CloudProvider.AWS, 2, 1, 0.01));
    }

    @Test
    void nullProvider_throws() {
        assertThrows(NullPointerException.class,
                () -> new InstanceType("t3.micro", null, 2, 1, 0.01));
    }

    @Test
    void zeroVcpu_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new InstanceType("t3.micro", CloudProvider.AWS, 0, 1, 0.01));
    }

    @Test
    void zeroMemory_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new InstanceType("t3.micro", CloudProvider.AWS, 2, 0, 0.01));
    }

    @Test
    void negativeHourlyUsd_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new InstanceType("t3.micro", CloudProvider.AWS, 2, 1, -0.01));
    }

    @Test
    void zeroHourlyUsd_isAllowed() {
        assertDoesNotThrow(() -> new InstanceType("free-tier", CloudProvider.AWS, 1, 0.5, 0.0));
    }

    @Test
    void equals_sameValues_returnsTrue() {
        InstanceType a = new InstanceType("m5.large", CloudProvider.AWS, 2, 8, 0.096);
        InstanceType b = new InstanceType("m5.large", CloudProvider.AWS, 2, 8, 0.096);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentName_returnsFalse() {
        InstanceType a = new InstanceType("m5.large",  CloudProvider.AWS, 2, 8, 0.096);
        InstanceType b = new InstanceType("m5.xlarge", CloudProvider.AWS, 2, 8, 0.096);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentProvider_returnsFalse() {
        InstanceType a = new InstanceType("m5.large", CloudProvider.AWS,  2, 8, 0.096);
        InstanceType b = new InstanceType("m5.large", CloudProvider.GCP,  2, 8, 0.096);
        assertNotEquals(a, b);
    }

    @Test
    void equals_null_returnsFalse() {
        InstanceType a = new InstanceType("m5.large", CloudProvider.AWS, 2, 8, 0.096);
        assertNotEquals(a, null);
    }

    @Test
    void equals_differentType_returnsFalse() {
        InstanceType a = new InstanceType("m5.large", CloudProvider.AWS, 2, 8, 0.096);
        assertNotEquals(a, "not an InstanceType");
    }

    @Test
    void equals_sameInstance_returnsTrue() {
        InstanceType a = new InstanceType("m5.large", CloudProvider.AWS, 2, 8, 0.096);
        assertEquals(a, a);
    }

    @Test
    void equals_differentVcpu_returnsFalse() {
        InstanceType a = new InstanceType("m5.large", CloudProvider.AWS, 2, 8, 0.096);
        InstanceType b = new InstanceType("m5.large", CloudProvider.AWS, 4, 8, 0.096);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentMemoryGib_returnsFalse() {
        InstanceType a = new InstanceType("m5.large", CloudProvider.AWS, 2, 8,  0.096);
        InstanceType b = new InstanceType("m5.large", CloudProvider.AWS, 2, 16, 0.096);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentHourlyUsd_returnsFalse() {
        InstanceType a = new InstanceType("m5.large", CloudProvider.AWS, 2, 8, 0.096);
        InstanceType b = new InstanceType("m5.large", CloudProvider.AWS, 2, 8, 0.192);
        assertNotEquals(a, b);
    }

    @Test
    void toString_containsNameAndProvider() {
        InstanceType inst = new InstanceType("c5.xlarge", CloudProvider.AWS, 4, 8, 0.17);
        String s = inst.toString();
        assertTrue(s.contains("c5.xlarge"));
        assertTrue(s.contains("AWS"));
    }
}
