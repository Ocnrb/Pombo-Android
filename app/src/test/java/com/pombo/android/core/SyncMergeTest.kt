package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases mirrored one-for-one against the web's syncMerge.js. Expected values
 * were produced by running the real JS module on the same inputs, not written
 * from reading it — the merge decides whether a leave survives a stale
 * snapshot, so a silent divergence here loses user data.
 */
class SyncMergeTest {

    private fun j(s: String) = JSONObject(s)

    @Test
    fun `leave beats an older join snapshot`() {
        val merged = SyncMerge.mergeState(
            j("""{"channels":[],"channelsLeftAt":{"s/a-1":2000},"sliceTs":{}}"""),
            j("""{"channels":[{"messageStreamId":"s/a-1","joinedAt":1000}],"channelsLeftAt":{},"sliceTs":{}}""")
        )
        assertEquals(0, merged.getJSONArray("channels").length())
        assertEquals(2000L, merged.getJSONObject("channelsLeftAt").getLong("s/a-1"))
    }

    @Test
    fun `rejoin beats the tombstone and prunes it`() {
        val merged = SyncMerge.mergeState(
            j("""{"channels":[],"channelsLeftAt":{"s/a-1":2000},"sliceTs":{}}"""),
            j("""{"channels":[{"messageStreamId":"s/a-1","joinedAt":3000}],"channelsLeftAt":{},"sliceTs":{}}""")
        )
        assertEquals(1, merged.getJSONArray("channels").length())
        assertEquals(3000L, merged.getJSONArray("channels").getJSONObject(0).getLong("joinedAt"))
        assertEquals(0, merged.getJSONObject("channelsLeftAt").length())
    }

    @Test
    fun `join wins a tie with the leave tombstone`() {
        val merged = SyncMerge.mergeState(
            j("""{"channels":[],"channelsLeftAt":{"s/a-1":1000},"sliceTs":{}}"""),
            j("""{"channels":[{"messageStreamId":"s/a-1","joinedAt":1000}],"channelsLeftAt":{},"sliceTs":{}}""")
        )
        assertEquals(1, merged.getJSONArray("channels").length())
    }

    @Test
    fun `an older slice does not clobber a newer local one`() {
        val merged = SyncMerge.mergeState(
            j("""{"username":"local","sliceTs":{"username":5000}}"""),
            j("""{"username":"remote","sliceTs":{"username":1000}}""")
        )
        assertEquals("local", merged.getString("username"))
        assertEquals(5000L, merged.getJSONObject("sliceTs").getLong("username"))
    }

    @Test
    fun `a newer slice wins`() {
        val merged = SyncMerge.mergeState(
            j("""{"username":"local","sliceTs":{"username":1000}}"""),
            j("""{"username":"remote","sliceTs":{"username":5000}}""")
        )
        assertEquals("remote", merged.getString("username"))
    }

    @Test
    fun `a null graph key from a pre-sliceTs client keeps the configured one`() {
        val merged = SyncMerge.mergeState(
            j("""{"graphApiKey":"abc","sliceTs":{}}"""),
            j("""{"graphApiKey":null,"sliceTs":{}}""")
        )
        assertEquals("abc", merged.getString("graphApiKey"))
        assertEquals(0L, merged.getJSONObject("sliceTs").getLong("graphApiKey"))
    }

    @Test
    fun `sent messages union by id and stay ordered`() {
        val merged = SyncMerge.mergeState(
            j("""{"sentMessages":{"s/a-1":[{"id":"m1","timestamp":2}]},"sliceTs":{}}"""),
            j("""{"sentMessages":{"s/a-1":[{"id":"m0","timestamp":1},{"id":"m1","timestamp":2}]},"sliceTs":{}}""")
        )
        val msgs = merged.getJSONObject("sentMessages").getJSONArray("s/a-1")
        assertEquals(2, msgs.length())
        assertEquals("m0", msgs.getJSONObject(0).getString("id"))
        assertEquals("m1", msgs.getJSONObject(1).getString("id"))
    }

    @Test
    fun `an emptied remote reaction map removes the entry`() {
        val merged = SyncMerge.mergeState(
            j("""{"sentReactions":{"s/a-1":{"m1":{"x":["0xa"]}}},"sliceTs":{}}"""),
            j("""{"sentReactions":{"s/a-1":{"m1":{}}},"sliceTs":{}}""")
        )
        assertEquals(0, merged.getJSONObject("sentReactions").length())
    }

    @Test
    fun `ens cache merges shallowly`() {
        val merged = SyncMerge.mergeState(
            j("""{"ensCache":{"0xa":{"name":"a.eth"}},"sliceTs":{}}"""),
            j("""{"ensCache":{"0xb":{"name":"b.eth"}},"sliceTs":{}}""")
        )
        val ens = merged.getJSONObject("ensCache")
        assertEquals("a.eth", ens.getJSONObject("0xa").getString("name"))
        assertEquals("b.eth", ens.getJSONObject("0xb").getString("name"))
    }

    /**
     * The reason the merge works on raw JSON: this client does not model
     * `sentMessages` blobs or `blockedPeers`, and rebuilding the payload from
     * local state would push those back empty and wipe them everywhere else.
     */
    @Test
    fun `slices this client does not manage survive a round trip`() {
        val merged = SyncMerge.mergeState(
            j("""{"blockedPeers":["0xdead"],"someFutureSlice":{"k":1},"sliceTs":{"blockedPeers":9}}"""),
            j("""{"channels":[],"sliceTs":{}}""")
        )
        assertEquals("0xdead", merged.getJSONArray("blockedPeers").getString(0))
        assertEquals(1, merged.getJSONObject("someFutureSlice").getInt("k"))
    }

    @Test
    fun `merging a series applies payloads in order`() {
        val merged = SyncMerge.mergeSeries(
            JSONObject("""{"username":"a","sliceTs":{"username":1}}"""),
            listOf(
                JSONObject("""{"username":"b","sliceTs":{"username":2}}"""),
                JSONObject("""{"username":"c","sliceTs":{"username":3}}""")
            )
        )
        assertEquals("c", merged.getString("username"))
    }

    @Test
    fun `defaults appear when neither side has a slice`() {
        val merged = SyncMerge.mergeState(JSONObject("{}"), JSONObject("{}"))
        assertEquals(0, merged.getJSONArray("blockedPeers").length())
        assertEquals(0, merged.getJSONObject("dmLeftAt").length())
        assertEquals(0, merged.getJSONObject("trustedContacts").length())
        assertTrue(merged.isNull("username"))
        assertTrue(merged.isNull("graphApiKey"))
    }
}
