package com.kilombino.pyblockwatch.joinblaket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Used commitments travel in the .kab backup and a restore only ever adds to them. */
class CommitmentBackupTest {
    @Test fun exportThenMergeKeepsEveryUsedCommitment() {
        val dir = Files.createTempDirectory("cmt").toFile()
        val old = FileCommitmentStore(File(dir, "old/commitments.json"))
        assertNull(old.export())
        old.markUsed("AA".repeat(32)); old.markUsed("bb".repeat(32))
        val backup = old.export()!!

        val restored = FileCommitmentStore(File(dir, "new/commitments.json"))
        restored.markUsed("cc".repeat(32)) // used on this phone after the backup was made
        restored.mergeFrom(backup)
        assertEquals(3, restored.count())
        listOf("aa", "bb", "cc").forEach { assertTrue(restored.isUsed(it.repeat(32))) }
        restored.mergeFrom(backup) // twice: nothing duplicated
        assertEquals(3, restored.count())
        dir.deleteRecursively()
    }
}
