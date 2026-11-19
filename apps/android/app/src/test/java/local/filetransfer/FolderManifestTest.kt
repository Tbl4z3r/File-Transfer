package local.filetransfer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FolderManifestTest {
    private val files = listOf(UploadFile("archivo.txt", 3, "Raíz/Sub/archivo.txt"), UploadFile("suelto.txt", 0))
    private val dirs = listOf("Raíz", "Raíz/Sub", "Raíz/Vacía")
    @Test fun roundTripPreservesNestedAndEmptyDirectories() {
        val manifest = uploadManifest(files, dirs)
        val batch = DownloadOfferBatch.parse(JSONObject(manifest.toString()).put("batch_id", "11111111-1111-4111-8111-111111111111"))
        assertEquals(files, batch.files)
        assertEquals(dirs, batch.directories)
        assertEquals("Raíz/Sub/archivo.txt", batch.files.first().relativePath)
        assertEquals(0, uploadManifest(emptyList(), listOf("Vacía")).getJSONArray("files").length())
    }
    @Test fun rejectsTraversalCaseCollisionsMissingOrDifferentlyCasedParents() {
        for (bad in listOf("/a", "a/", "a//b", "a/../b", "a\\b", ".", "a/CON", "a/" + "x".repeat(181), List(17) { "a" }.joinToString("/"), List(4) { "x".repeat(150) }.joinToString("/"))) {
            assertFalse(bad, validRelativePath(bad))
        }
        for (badDirs in listOf(listOf("a", "A"), listOf("a/b"), listOf("a", "A/b"), List(65) { "d$it" })) {
            assertTrue(badDirs.toString(), runCatching { uploadManifest(emptyList(), badDirs) }.isFailure)
        }
        for (badFiles in listOf(
            listOf(UploadFile("x", 0, "raíz/Sub/x")),
            listOf(UploadFile("Sub", 0, "Raíz/Sub")),
            listOf(UploadFile("x", 0, "Raíz/x"), UploadFile("X", 0, "Raíz/X")),
            listOf(UploadFile("RAÍZ", 0)),
            listOf(UploadFile("x", 0, "Raíz/Unknown/x")),
        )) assertTrue(runCatching { uploadManifest(badFiles, dirs) }.isFailure)
        assertTrue(runCatching { uploadManifest(listOf(UploadFile("x", 0, "a/x"), UploadFile("y", 0)), emptyList()) }.isFailure)
        assertTrue(runCatching { UploadFile("x", 0, "a/y") }.isFailure)
    }
    @Test fun limitsEncodedBytesAndAcceptsOneFileOnlyWithFolders() {
        assertTrue(runCatching { uploadManifest(files.take(1), dirs) }.isSuccess)
        assertTrue(runCatching { uploadManifest(listOf(UploadFile("x", 0))) }.isFailure)
        val longNames = List(64) { "\u2028".repeat(58) + it }
        assertTrue(runCatching { uploadManifest(emptyList(), longNames) }.isFailure)
        val id = "11111111-1111-4111-8111-111111111111"
        for (manifest in listOf(
            JSONObject().put("batch_id", id).put("files", JSONArray()).put("directories", JSONArray()),
            JSONObject().put("batch_id", id).put("files", JSONArray()).put("directories", JSONArray().put(3)),
            JSONObject().put("batch_id", id).put("files", JSONArray()).put("directories", JSONArray().put("Raíz")).put("unknown", true),
        )) assertTrue(runCatching { DownloadOfferBatch.parse(manifest) }.isFailure)
    }
}
