# CLAUDE.md

Read [AGENTS.md](AGENTS.md) first. It is the maintained guide for this repo: layout, setup,
commands, SDK quirks, release rules, and the conventions the code relies on. Everything there
applies to Claude Code sessions.

Claude Code specifics:

- Build and test with JDK 17: `JAVA_HOME=<jdk17> ./gradlew testDebugUnitTest assembleDebug`.
  Run the unit tests before reporting a change as done.
- Bump `versionCode` (and `versionName`) in `app/build.gradle` in any change that will be shipped
  to a server; the updater ignores an APK whose `versionCode` did not increase.
- Never touch the BLE protocol directly or modify `app/libs/plaud-sdk.aar` by hand. Device access
  goes through the SDK only.
- Never commit `local.properties`, keystores, or anything with real names, hostnames, tokens, or
  private paths. Fixtures use fictional names.
- When you add a manager, a convention, or an SDK quirk, update `AGENTS.md` and the README in the
  same change.
