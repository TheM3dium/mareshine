# Releases

Mareshine updates itself from this repository's GitHub releases (`Updates.java`): the APK attached to a release is downloaded, checked against the app's signing key and handed to Android's installer.

## Stable and beta

- A **stable** release has a plain version tag, such as `v2.0.1`.
- A **beta** has a pre-release suffix, such as `v2.1.0-beta.1`. It comes before its version: `2.1.0-beta.1` < `2.1.0-beta.2` < `2.1.0`.
- The app offers GitHub's *latest* release, and with **Settings → OTHER → UPDATES → Offer beta versions** on, the newest of all releases, betas among them. The switch is on by default while a beta is installed.
- A beta that should reach everyone on the stable version is published as a normal release (GitHub's latest). Later betas can be pre-releases: only those who offer betas get them.

## Going back to stable

Android installs no APK with a lower `versionCode` over a higher one, and uninstalling deletes the data folder on phones (`Android/data/…`).
So a beta's **Back to stable version** button installs the newest stable release, which must have a **higher versionCode than every beta**.
That is why the stable version a beta goes back to has a versionCode far above the betas' (1000), and the next stable version one above that.

| version | versionCode |
| --- | --- |
| 2.0.0 | 19 |
| 2.1.0-beta.1 | 20 |
| 2.0.1 (2.0.0 again, for going back; offers no betas) | 21 |
| 2.1.0-beta.2 | 22 |
| 2.0.2 (as 2.0.1, above every beta to come) | 1000 |
| betas of 2.1 | 23 to 999 |
| 2.1.0 | 1001 |
