# Submitting Narrator to F-Droid

The repo already contains the metadata F-Droid needs (`metadata/com.example.narrator.yml`,
per-version changelogs, full + short descriptions). What's left is submitting a
build recipe to [fdroiddata](https://gitlab.com/fdroid/fdroiddata) so their build
server picks the app up.

## Prerequisites

1. **At least one tagged release** in this repo. `v0.10.x` works.
2. **Screenshots in place** under `metadata/en-US/images/phoneScreenshots/` (see
   the README there for the capture script).
3. **`icon.png`** under `metadata/en-US/images/` (export from Android Studio).
4. A GitLab account.

## Steps

1. Fork https://gitlab.com/fdroid/fdroiddata.
2. Clone your fork, create a branch: `git checkout -b add-narrator`.
3. Create `metadata/com.example.narrator.yml` (in the fdroiddata repo) with this
   content — it tells the build server what to do with each tag:

```yaml
Categories:
  - Multimedia
  - Reading
License: Apache-2.0
AuthorName: Stijn Nefkens
SourceCode: https://github.com/stijn-nefkens/narrator
IssueTracker: https://github.com/stijn-nefkens/narrator/issues

AutoName: Narrator

RepoType: git
Repo: https://github.com/stijn-nefkens/narrator.git

Builds:
  - versionName: '0.10.1'   # bump as you release
    versionCode: 13
    commit: v0.10.1
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: '0.10.1'
CurrentVersionCode: 13
```

The Builds list grows over time — one entry per tagged release. F-Droid's
fdroidserver does the auto-update via the `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`
declarations: when a new `v*` tag appears upstream, it generates the next Builds
entry automatically (you don't have to keep editing this file forever).

4. Commit + push your branch.
5. Open a merge request against fdroid/fdroiddata.
6. Their CI runs `fdroid lint metadata/com.example.narrator.yml` and `fdroid build`.
   Address any feedback; the typical first-time issues are missing icons,
   non-reproducible builds, or AndroidManifest permissions that aren't documented.
7. A maintainer reviews. Once merged, the next F-Droid build cycle picks the app
   up and publishes it.

## Murena App Lounge

Murena (the company behind /e/OS) runs their own App Lounge that re-distributes
F-Droid + open-source apps. Once Narrator is on F-Droid, App Lounge picks it up
automatically — no separate submission needed.

## Reproducible builds

F-Droid prefers reproducible builds: their server build of `v0.10.1` should byte-
identical the local release APK. The current `app/build.gradle.kts` should be
close — the things that typically break reproducibility:

- `android:debuggable` differing between local + CI builds.
- Embedded timestamps in resources.
- Different versions of build tools between local + F-Droid's server (it pins to
  the AGP / NDK versions you declare).

If F-Droid's build fails reproducibility for now, that's fine — the app will still
be published, just not signed with the upstream signing key on their side.
