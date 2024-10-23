package com.example.fingerprintnfcmiddleware

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        val id: ByteArray = byteArrayOf(0xe4.toByte(), 0x77.toByte(), 0x8e.toByte(), 0xaa.toByte())
        val newId: ULong = id[2].toUByte() * 100000u + id[1].toUByte() * (0x100).toULong() + id[0].toUByte()
        print(newId)
        assertEquals(14230692uL, newId)
    }
}