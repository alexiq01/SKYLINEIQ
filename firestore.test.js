const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_uid_001";
const ADMIN_EMAIL = "yassindevai01@gmail.com";
const ALICE_UID = "alice_uid_002";
const BOB_UID = "bob_uid_003";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function adminContext() {
  return testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  });
}

function spoofedUnverifiedAdminContext() {
  return testEnv.authenticatedContext("spoof_uid_999", {
    email: ADMIN_EMAIL,
    email_verified: false,
  });
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

test("Admin account: verified admin can create/read/update own doc; non-admin & spoofed email cannot", async () => {
  const now = new Date(Date.now() - 2000);
  const adminDb = adminContext().firestore();

  await assertSucceeds(
    adminDb.collection("admins").doc(ADMIN_UID).set({
      uid: ADMIN_UID,
      email: ADMIN_EMAIL,
      adminUsername: "skyline_admin",
      passwordSalt: "a1b2c3d4e5f60718",
      passwordHash: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(adminDb.collection("admins").doc(ADMIN_UID).get());

  await assertSucceeds(
    adminDb.collection("admins").doc(ADMIN_UID).update({
      adminUsername: "skyline_boss",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Spoofed unverified email cannot create admin record
  const spoofDb = spoofedUnverifiedAdminContext().firestore();
  await assertFails(
    spoofDb.collection("admins").doc("spoof_uid_999").set({
      uid: "spoof_uid_999",
      email: ADMIN_EMAIL,
      adminUsername: "attacker",
      passwordSalt: "a1b2c3d4e5f60718",
      passwordHash: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Regular user cannot read admin credentials
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  await assertFails(aliceDb.collection("admins").doc(ADMIN_UID).get());
  await assertFails(aliceDb.collection("admins").get());
});

test("Users directory: owner writes & filtered list succeed; cross-user & unfiltered list fail; admin can list", async () => {
  const now = new Date(Date.now() - 2000);
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  // Unauthenticated rejected
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      appVersionName: "1.0.4",
      appVersionCode: 4,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Alice creates own record (without @DocumentId id key, optional ispUsername omitted or present)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      ispUsername: "bbn.17@haq2",
      appVersionName: "1.0.4",
      appVersionCode: 4,
      launcherPreset: "glass_blue",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Shadow update with ghost field rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Updated",
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Alice filtered query succeeds; unfiltered query fails
  await assertSucceeds(
    aliceDb.collection("users").where("userId", "==", ALICE_UID).get()
  );
  await assertFails(aliceDb.collection("users").get());

  // Bob cannot read or overwrite Alice
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Admin can list all users to count active users
  const adminDb = adminContext().firestore();
  await assertSucceeds(adminDb.collection("users").get());
});

test("App Config: public get on main succeeds; only verified admin can create/update; title > 30 chars fails", async () => {
  const now = new Date(Date.now() - 2000);
  const adminDb = adminContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  const validConfig = {
    appDisplayName: "Skyline Users",
    appSubtitle: "SKYLINE INTERNET",
    launcherPreset: "emerald_pro",
    latestVersionCode: 5,
    latestVersionName: "1.0.5",
    updateUrl: "https://example.com/skyline-1.0.5.apk",
    forceUpdate: true,
    updateTitle: "تحديث جديد متوفر",
    updateMessage: "يرجى التحديث للحصول على المزايا الجديدة",
    releaseNotes: "تحسينات عامة",
    updatedByUid: ADMIN_UID,
    createdAt: now,
    updatedAt: now,
  };

  // Non-admin cannot create config
  await assertFails(aliceDb.collection("app_config").doc("main").set(validConfig));

  // Admin creates config
  await assertSucceeds(adminDb.collection("app_config").doc("main").set(validConfig));

  // Unauthenticated client can get singleton 'main' doc (not list!)
  await assertSucceeds(unauthDb.collection("app_config").doc("main").get());
  await assertFails(unauthDb.collection("app_config").get());

  // Admin partial update succeeds
  await assertSucceeds(
    adminDb.collection("app_config").doc("main").update({
      appDisplayName: "Skyline Pro",
      launcherPreset: "royal_gold",
      updatedByUid: ADMIN_UID,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Play policy bound: appDisplayName > 30 chars must fail
  await assertFails(
    adminDb.collection("app_config").doc("main").update({
      appDisplayName: "This App Name Is Definitely Over Thirty Characters Long",
      updatedByUid: ADMIN_UID,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Broadcasts: admin publishes & deletes; users can list only published == true", async () => {
  const now = new Date(Date.now() - 2000);
  const adminDb = adminContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  await assertSucceeds(
    adminDb.collection("broadcasts").doc("bcast_001").set({
      title: "عرض خاص",
      body: "خصم 20% على باقة Nitro",
      category: "fcm_offer",
      published: true,
      senderUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    adminDb.collection("broadcasts").doc("bcast_draft").set({
      title: "مسودة",
      body: "غير منشور",
      category: "fcm_general",
      published: false,
      senderUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Non-admin cannot create or delete broadcasts
  await assertFails(
    aliceDb.collection("broadcasts").doc("bcast_fake").set({
      title: "اختراق",
      body: "رسالة مزيفة",
      category: "fcm_general",
      published: true,
      senderUid: ALICE_UID,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(aliceDb.collection("broadcasts").doc("bcast_001").delete());

  // Filtered query for published == true succeeds; unfiltered query fails for non-admin
  await assertSucceeds(
    unauthDb.collection("broadcasts").where("published", "==", true).get()
  );
  await assertFails(unauthDb.collection("broadcasts").get());
  await assertFails(unauthDb.collection("broadcasts").doc("bcast_draft").get());

  // Admin can list all and delete
  await assertSucceeds(adminDb.collection("broadcasts").get());
  await assertSucceeds(adminDb.collection("broadcasts").doc("bcast_001").delete());
});
