package com.example.data.firestore

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FaqqaRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createProjectAndChapter_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = FaqqaFirestoreRepository(firestore, auth)

        val profileRes = withTimeout(TIMEOUT_MS) { repo.ensureUserProfile("Alice") }
        assertTrue(profileRes.isSuccess)

        val projRes = withTimeout(TIMEOUT_MS) {
            repo.createProject(ComicProject(title = "One Piece AR", sourceLang = "JA"))
        }
        assertTrue(projRes.isSuccess)
        val project = projRes.getOrThrow()

        val chapRes = withTimeout(TIMEOUT_MS) {
            repo.createChapter(
                ChapterRecord(
                    projectId = project.id,
                    title = "الفصل الأول",
                    chapterNumber = 1L,
                    importSource = "file_import",
                    pageCount = 3L
                )
            )
        }
        assertTrue(chapRes.isSuccess)

        val glossaryRes = withTimeout(TIMEOUT_MS) {
            repo.addGlossaryEntry(
                GlossaryEntry(
                    projectId = project.id,
                    sourceTerm = "Luffy",
                    targetTerm = "لوفي",
                    category = "character"
                )
            )
        }
        assertTrue(glossaryRes.isSuccess)

        val observedProjects = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeProjects(uid).first { list -> list.any { it.id == project.id } }
        }
        assertTrue(observedProjects.any { it.id == project.id })
    }

    @Test
    fun observeProjects_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = FaqqaFirestoreRepository(firestore, auth)
        withTimeout(TIMEOUT_MS) {
            aliceRepo.createProject(ComicProject(title = "Alice Manga")).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = FaqqaFirestoreRepository(firestore, auth)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeProjects(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED for cross-user query")
        } catch (e: Exception) {
            val fsError = e.unwrapFirestoreException()
                ?: throw AssertionError("Expected FirebaseFirestoreException, got $e")
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError.code)
        }
    }

    @Test
    fun observeProjects_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = FaqqaFirestoreRepository(firestore, auth)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeProjects("alice_fake_uid").first()
            }
            fail("Expected PERMISSION_DENIED when unauthenticated")
        } catch (e: Exception) {
            val fsError = e.unwrapFirestoreException()
                ?: throw AssertionError("Expected FirebaseFirestoreException, got $e")
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsError.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice_faqqa@test.com"
        const val BOB_EMAIL = "bob_faqqa@test.com"
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3500L
    }
}
