package com.anve.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGenerationStoreTest {
    @Test fun `checkpoint is account scoped and rejects delayed provider callbacks`() {
        var now = 100L
        val store = CaptureGenerationStore { now }
        assertEquals(0L, store.current("a", 99))
        store.advance("a", 2)
        assertEquals(0L, store.current("a", 99))
        assertEquals(2L, store.current("a", 100))
        assertEquals(0L, store.current("b", 200))
        now = 200
        store.advance("a", 1)
        assertEquals(2L, store.current("a", 150))
        store.advance("a", 3)
        assertEquals(0L, store.current("a", 199))
        assertEquals(3L, store.current("a", 200))
    }
}
