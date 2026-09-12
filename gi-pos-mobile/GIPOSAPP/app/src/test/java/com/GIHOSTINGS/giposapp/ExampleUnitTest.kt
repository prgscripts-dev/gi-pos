package com.GIHOSTINGS.giposapp

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.ZoneId

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class BillingCoreTest {
    @Test
    fun money_rounds_to_minor_units() {
        assertEquals(1001L, MoneyMath.toMinor(10.005))
        assertEquals(10.01, MoneyMath.round(10.005), 0.0)
    }

    @Test
    fun inclusive_and_exclusive_tax_are_stable() {
        assertEquals(500L, MoneyMath.inclusiveTax(10500, 5.0))
        assertEquals(500L, MoneyMath.exclusiveTax(10000, 5.0))
        assertEquals(1333L, MoneyMath.percentOf(13333, 10.0))
    }

    @Test
    fun bill_period_resets_on_april_first() {
        assertEquals("2025-26", PosDatabase.financialYearKey(time("2026-03-31")))
        assertEquals("2026-27", PosDatabase.financialYearKey(time("2026-04-01")))
    }

    @Test
    fun kot_period_resets_each_day() {
        assertEquals("2026-08-14", PosDatabase.dailyKey(time("2026-08-14")))
        assertEquals("2026-08-15", PosDatabase.dailyKey(time("2026-08-15")))
    }

    @Test
    fun optional_pin_is_salted_and_verified_without_plaintext_storage() {
        val first = CredentialSecurity.hashPin("2468")
        val second = CredentialSecurity.hashPin("2468")
        assertNotEquals(first, second)
        assertFalse(first.contains("2468"))
        assertTrue(CredentialSecurity.verifyPin("2468", first))
        assertFalse(CredentialSecurity.verifyPin("1357", first))
    }

    @Test
    fun pos58_receipt_contains_bill_content_and_cut_command() {
        val bytes = EscPosFormatter.format("bill", receiptPayload().toString(), 58)
        val printable = String(bytes, Charset.forName("windows-1252"))
        assertTrue(bytes.size > 120)
        assertTrue(printable.contains("Bill #"))
        assertTrue(printable.contains("KOT #"))
        assertTrue(printable.contains("Chicken"))
        assertTrue(printable.contains("Biriyani"))
        assertTrue(printable.contains("Qty"))
        assertTrue(printable.contains("Price"))
        assertTrue(printable.contains("Amount"))
        assertTrue(printable.contains("Total Qty"))
        assertTrue(printable.contains("GRAND TOTAL"))
        assertCutCommand(bytes)
    }

    @Test
    fun pos80_kot_contains_items_and_cut_command() {
        val payload = JSONObject()
            .put("kotNumber", 18)
            .put("createdAt", System.currentTimeMillis())
            .put("table", "T4")
            .put("operator", "Owner")
            .put("items", JSONArray().put(JSONObject().put("name", "Fresh Lime").put("variant", "Large").put("quantity", 2).put("note", "Less ice")))
        val bytes = EscPosFormatter.format("kot", payload.toString(), 80)
        val printable = String(bytes, Charset.forName("windows-1252"))
        assertTrue(bytes.size > 80)
        assertTrue(printable.contains("KOT #18"))
        assertTrue(printable.contains("Fresh Lime"))
        assertTrue(printable.contains("Less ice"))
        assertCutCommand(bytes)
    }

    private fun receiptPayload(): JSONObject = JSONObject()
        .put("businessName", "GI Restaurant")
        .put("billNumber", 42)
        .put("kotNumber", 7)
        .put("createdAt", System.currentTimeMillis())
        .put("table", "T2")
        .put("operator", "Owner")
        .put("items", JSONArray().put(JSONObject().put("name", "Chicken Biriyani").put("variant", "").put("quantity", 2).put("unitPrice", 150.0).put("lineTotal", 300.0)))
        .put("totals", JSONObject().put("subtotal", 300.0).put("tax", 0).put("discount", 0).put("total", 300.0).put("due", 0).put("balance", 0))

    private fun assertCutCommand(bytes: ByteArray) {
        assertTrue(bytes.size >= 4)
        assertEquals(29, bytes[bytes.size - 4].toInt() and 0xff)
        assertEquals(86, bytes[bytes.size - 3].toInt() and 0xff)
        assertEquals(66, bytes[bytes.size - 2].toInt() and 0xff)
        assertEquals(0, bytes[bytes.size - 1].toInt() and 0xff)
    }

    private fun time(date: String): Long = LocalDate.parse(date)
        .atTime(12, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}
