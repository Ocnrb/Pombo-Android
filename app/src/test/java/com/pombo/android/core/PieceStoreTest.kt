package com.pombo.android.core

import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PieceStoreTest {

    private lateinit var dir: File

    private val fileId = "550e8400-e29b-41d4-a716-446655440000"

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "piecestore-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Deterministic bytes for piece [index] of a file of [size]. */
    private fun piece(size: Long, index: Int): ByteArray =
        ByteArray(MediaConfig.pieceLength(size, index)) { ((it + index * 7) and 0xff).toByte() }

    private fun hashOf(bytes: ByteArray) = PieceStore.sha256Hex(bytes)

    // ==================== geometry ====================

    @Test
    fun `piece geometry matches the web's slicing`() {
        // Exactly two full pieces.
        val exact = 2L * MediaConfig.PIECE_SIZE
        assertEquals(2, MediaConfig.pieceCount(exact))
        assertEquals(MediaConfig.PIECE_SIZE, MediaConfig.pieceLength(exact, 1))

        // One byte more needs a third piece holding a single byte — the case
        // where an off-by-one in the ceiling silently drops the tail.
        assertEquals(3, MediaConfig.pieceCount(exact + 1))
        assertEquals(1, MediaConfig.pieceLength(exact + 1, 2))

        // Smaller than one piece is still one piece, not zero.
        assertEquals(1, MediaConfig.pieceCount(1))
        assertEquals(1, MediaConfig.pieceLength(1, 0))

        // Past the end has no length rather than a negative one.
        assertEquals(0, MediaConfig.pieceLength(exact, 5))
    }

    // ==================== writing and reading ====================

    @Test
    fun `writes and reads back every piece`() {
        val size = 3L * MediaConfig.PIECE_SIZE + 17
        val store = PieceStore.open(dir, fileId, size)
        assertEquals(4, store.pieceCount)
        assertFalse(store.isComplete())

        for (i in 0 until store.pieceCount) {
            val bytes = piece(size, i)
            assertTrue("piece $i accepted", store.writePiece(i, bytes, hashOf(bytes)))
            assertTrue(store.hasPiece(i))
            assertArrayEquals(bytes, store.readPiece(i))
        }
        assertTrue(store.isComplete())
        assertEquals(size, store.bytesOnDisk())
        store.close()
    }

    @Test
    fun `pieces land at the right offsets`() {
        // The real test of a sparse file: writing out of order must still
        // produce the same bytes as writing in order.
        val size = 3L * MediaConfig.PIECE_SIZE
        val store = PieceStore.open(dir, fileId, size)
        for (i in listOf(2, 0, 1)) {
            val bytes = piece(size, i)
            store.writePiece(i, bytes, hashOf(bytes))
        }
        val out = File(dir, "out.bin")
        store.finish(out)

        val written = out.readBytes()
        assertEquals(size, written.size.toLong())
        for (i in 0 until 3) {
            val start = i * MediaConfig.PIECE_SIZE
            assertArrayEquals(
                "piece $i region",
                piece(size, i),
                written.copyOfRange(start, start + MediaConfig.PIECE_SIZE)
            )
        }
    }

    @Test
    fun `readPiece returns null for a piece not held`() {
        val store = PieceStore.open(dir, fileId, 2L * MediaConfig.PIECE_SIZE)
        assertNull(store.readPiece(1))
        assertNull("out of range", store.readPiece(99))
        store.close()
    }

    // ==================== integrity ====================

    @Test
    fun `rejects a piece whose hash does not match`() {
        val size = 2L * MediaConfig.PIECE_SIZE
        val store = PieceStore.open(dir, fileId, size)
        val good = piece(size, 0)
        val tampered = good.copyOf().also { it[100] = (it[100] + 1).toByte() }

        assertFalse(store.writePiece(0, tampered, hashOf(good)))
        // Rejected means NOT written: a store that recorded it would never
        // re-request the piece, and the corruption would survive to assembly.
        assertFalse(store.hasPiece(0))
        assertNull(store.readPiece(0))
        store.close()
    }

    @Test
    fun `rejects a piece of the wrong length`() {
        val size = 2L * MediaConfig.PIECE_SIZE + 5
        val store = PieceStore.open(dir, fileId, size)
        // Full-size bytes offered as the short LAST piece. The hash matches what
        // was offered, so only the length check can catch this one.
        val wrong = ByteArray(MediaConfig.PIECE_SIZE)
        assertFalse(store.writePiece(2, wrong, hashOf(wrong)))
        assertFalse(store.hasPiece(2))
        store.close()
    }

    @Test
    fun `accepts a piece when no hash is known`() {
        // Seeding our own file: the bytes are ours, there is nothing to verify.
        val size = MediaConfig.PIECE_SIZE.toLong()
        val store = PieceStore.open(dir, fileId, size)
        assertTrue(store.writePiece(0, piece(size, 0), null))
        store.close()
    }

    // ==================== resume ====================

    @Test
    fun `resumes from disk after being closed`() {
        val size = 4L * MediaConfig.PIECE_SIZE
        PieceStore.open(dir, fileId, size).use { first ->
            for (i in listOf(0, 2)) {
                val bytes = piece(size, i)
                first.writePiece(i, bytes, hashOf(bytes))
            }
        }

        PieceStore.open(dir, fileId, size).use { resumed ->
            assertEquals(2, resumed.completedPieces())
            assertTrue(resumed.hasPiece(0))
            assertFalse(resumed.hasPiece(1))
            assertTrue(resumed.hasPiece(2))
            assertEquals(listOf(1, 3), resumed.missingPieces())
            // The bytes survived too, not just the bookkeeping.
            assertArrayEquals(piece(size, 2), resumed.readPiece(2))
        }
    }

    // ==================== completion ====================

    @Test(expected = IOException::class)
    fun `refuses to finish an incomplete transfer`() {
        // The dangerous case: a sparse file reads as zeros where nothing was
        // written, so an incomplete file looks perfectly well-formed. Publishing
        // it would hand the user a silently corrupt file.
        val size = 2L * MediaConfig.PIECE_SIZE
        val store = PieceStore.open(dir, fileId, size)
        val bytes = piece(size, 0)
        store.writePiece(0, bytes, hashOf(bytes))
        store.finish(File(dir, "out.bin"))
    }

    @Test
    fun `finish produces a file of exactly the declared size`() {
        val size = MediaConfig.PIECE_SIZE + 123L
        val store = PieceStore.open(dir, fileId, size)
        for (i in 0 until store.pieceCount) {
            val bytes = piece(size, i)
            store.writePiece(i, bytes, hashOf(bytes))
        }
        val out = store.finish(File(dir, "done.bin"))
        assertEquals(size, out.length())
        // The scratch files are gone, not left behind to fill the disk.
        assertFalse(File(dir, "$fileId.part").exists())
        assertFalse(File(dir, "$fileId.bits").exists())
    }

    @Test
    fun `export copies the file without consuming it`() {
        val size = 2L * MediaConfig.PIECE_SIZE + 99
        val store = PieceStore.open(dir, fileId, size)
        for (i in 0 until store.pieceCount) {
            val bytes = piece(size, i)
            store.writePiece(i, bytes, hashOf(bytes))
        }

        val sink = java.io.ByteArrayOutputStream()
        store.exportTo(sink)
        assertEquals(size, sink.size().toLong())

        // The point of exporting rather than renaming: this device must still
        // be able to serve the file. A save that stopped us seeding would cost
        // the swarm a seeder every time somebody kept a copy.
        assertTrue(store.isComplete())
        assertArrayEquals(piece(size, 1), store.readPiece(1))

        // And the bytes came out in the right order.
        val written = sink.toByteArray()
        assertArrayEquals(
            piece(size, 2),
            written.copyOfRange(2 * MediaConfig.PIECE_SIZE, written.size)
        )
        store.close()
    }

    @Test(expected = IOException::class)
    fun `refuses to export an incomplete transfer`() {
        val size = 2L * MediaConfig.PIECE_SIZE
        val store = PieceStore.open(dir, fileId, size)
        val bytes = piece(size, 0)
        store.writePiece(0, bytes, hashOf(bytes))
        store.exportTo(java.io.ByteArrayOutputStream())
    }

    @Test
    fun `discard removes everything`() {
        val store = PieceStore.open(dir, fileId, 2L * MediaConfig.PIECE_SIZE)
        val bytes = piece(2L * MediaConfig.PIECE_SIZE, 0)
        store.writePiece(0, bytes, hashOf(bytes))
        store.discard()
        assertFalse(File(dir, "$fileId.part").exists())
        assertFalse(File(dir, "$fileId.bits").exists())
    }

    // ==================== id safety ====================

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a file id that could escape the directory`() {
        // The id comes off the wire and names two files on disk.
        PieceStore.open(dir, "../../etc/passwd", 1024)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty file id`() {
        PieceStore.open(dir, "", 1024)
    }

    // ==================== hashing ====================

    @Test
    fun `sha256Hex matches the known digest of an empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PieceStore.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun `sha256Hex matches the known digest of abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PieceStore.sha256Hex("abc".toByteArray())
        )
    }
}
