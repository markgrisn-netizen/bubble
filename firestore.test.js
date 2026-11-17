const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function nowTs() {
  return new Date(Date.now() - 1000);
}

function validProjectPayload(uid, overrides = {}) {
  return {
    userId: uid,
    title: "Solo Leveling AR",
    sourceLang: "KO",
    targetLang: "AR",
    translationStyle: "natural",
    readingDirection: "rtl",
    readerMode: "vertical_webtoon",
    sfxMode: "side_note",
    cleanBackgroundText: false,
    pageCount: 12,
    contextNotes: "Sung Jin-Woo speaks calmly.",
    createdAt: nowTs(),
    updatedAt: nowTs(),
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write projects", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("projects").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_1")
      .set(validProjectPayload(ALICE_UID))
  );
});

test("Authenticated owner can create, read, update, and delete own project", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const projRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("projects")
    .doc("proj_1");

  await assertSucceeds(projRef.set(validProjectPayload(ALICE_UID)));
  await assertSucceeds(projRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("projects").get()
  );
  await assertSucceeds(
    projRef.update({ title: "Updated Title", updatedAt: nowTs() })
  );
  await assertSucceeds(projRef.delete());
});

test("Cross-user access is denied on projects and chapters", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_1")
      .set(validProjectPayload(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_1")
      .get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("projects").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_2")
      .set(validProjectPayload(BOB_UID))
  );
});

test("Shadow update with ghost field and invalid enum are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const projRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("projects")
    .doc("proj_shadow");

  await assertFails(
    projRef.set(validProjectPayload(ALICE_UID, { isAdmin: true }))
  );
  await assertFails(
    projRef.set(validProjectPayload(ALICE_UID, { translationStyle: "hacked" }))
  );
  await assertSucceeds(projRef.set(validProjectPayload(ALICE_UID)));
  await assertFails(projRef.update({ ghostField: 123, updatedAt: nowTs() }));
});

test("Chapter creation requires parent project existence (Parent Gate)", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const chapterPayload = {
    userId: ALICE_UID,
    projectId: "missing_proj",
    title: "Ch 1",
    chapterNumber: 1,
    importSource: "file_import",
    sourceUrl: "",
    pageCount: 5,
    lastReadPageIndex: 0,
    isOfflineReady: true,
    createdAt: nowTs(),
    updatedAt: nowTs(),
  };

  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("missing_proj")
      .collection("chapters")
      .doc("ch_1")
      .set(chapterPayload)
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_exists")
      .set(validProjectPayload(ALICE_UID))
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_exists")
      .collection("chapters")
      .doc("ch_1")
      .set({ ...chapterPayload, projectId: "proj_exists" })
  );
});

test("TranslationJob enforces terminal state lock after completed or failed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const jobRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("translationJobs")
    .doc("job_1");

  await assertSucceeds(
    jobRef.set({
      userId: ALICE_UID,
      projectId: "proj_1",
      chapterId: "ch_1",
      providerId: "firebase_ai",
      status: "running",
      processedPages: 1,
      totalPages: 5,
      errorMessage: "",
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  await assertSucceeds(
    jobRef.update({
      status: "completed",
      processedPages: 5,
      updatedAt: nowTs(),
    })
  );

  await assertFails(
    jobRef.update({
      status: "running",
      processedPages: 2,
      updatedAt: nowTs(),
    })
  );
});
