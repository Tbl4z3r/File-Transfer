package local.filetransfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class PrivateFolderPlanTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun createsNewRootPreservesEmptyFoldersAndPublishesInAssignedParent() {
        val inbox = temporary.newFolder()
        File(inbox, "Root").mkdir()
        File(inbox, "Root/old.txt").writeText("existing")
        val plan = PrivateFolderPlan.prepare(inbox, listOf("Root", "Root/Sub", "Root/Empty")) { false }
        assertEquals("Root (1)", plan.getValue("Root").name)
        assertTrue(plan.getValue("Root/Empty").isDirectory)
        val source = temporary.newFile().apply { writeText("abc") }
        val hash = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).hex()
        val published = PrivateInboxPublisher.publish(source, plan.getValue("Root/Sub"), "file.txt", 3, hash)
        assertEquals("abc", published.readText())
        assertEquals("existing", File(inbox, "Root/old.txt").readText())
        assertEquals(plan.getValue("Root/Sub"), published.parentFile)
    }
    @Test fun existingSymlinkIsNeverFollowedAndSymlinkInboxIsRejected() {
        val inbox = temporary.newFolder()
        val outside = temporary.newFolder()
        Files.createSymbolicLink(File(inbox, "Root").toPath(), outside.toPath())
        val plan = PrivateFolderPlan.prepare(inbox, listOf("Root", "Root/Sub")) { false }
        assertEquals("Root (1)", plan.getValue("Root").name)
        assertTrue(outside.list()!!.isEmpty())
        val linkedInbox = File(temporary.root, "linked")
        Files.createSymbolicLink(linkedInbox.toPath(), outside.toPath())
        assertTrue(runCatching { PrivateFolderPlan.prepare(linkedInbox, listOf("x")) { false } }.isFailure)
    }
    @Test fun cancellationRemovesOnlyNewEmptyDirectories() {
        val inbox = temporary.newFolder()
        File(inbox, "Keep").mkdir()
        var checks = 0
        assertTrue(runCatching { PrivateFolderPlan.prepare(inbox, listOf("Root", "Root/Sub", "Root/Empty")) { ++checks > 2 } }.isFailure)
        assertEquals(listOf("Keep"), inbox.list()!!.toList())
    }
    @Test fun maximumUtf8RootCanBeNumberedWithoutExceedingNameLimit() {
        val name = "á".repeat(90)
        val numbered = numberedFolderName(name, 1)
        assertTrue(Validation.name(numbered))
        assertTrue(numbered.toByteArray(Charsets.UTF_8).size <= 180)
        assertTrue(numbered.endsWith(" (1)"))
    }
}
