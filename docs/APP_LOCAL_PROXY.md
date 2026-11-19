# App-local proxy on Android

On first launch, BitChord offers **Find WireGuard .conf file** and **Continue without WireGuard** before account setup. A fresh install without a profile uses the device’s normal connection. You can also open **Proxy** from the signed-out Home screen or Settings. On Android TV, **Proxy** is available from the welcome screen and Settings.

## Set up WireGuard

1. Select a WireGuard `.conf` file with the Android document picker.
2. BitChord validates the file and saves it encrypted in its private, backup-excluded directory. Android Keystore holds the encryption key.
3. Confirm the restart. BitChord starts the tunnel before initializing its shared network client. During phone setup, you can then sign in with microG or skip sign-in.

If the selected profile is missing or the tunnel cannot start, the music client keeps the protected route blocked. Import another profile or choose **Proxy → Off → Apply** and confirm the restart to use the normal connection. Earlier installations with a saved HTTP or SOCKS5 endpoint keep that route until another is selected.

## Network coverage

The embedded userspace WireGuard engine runs an authenticated HTTP proxy on a random loopback port. The shared OkHttp client uses it for YouTube Music API requests, Media3 streaming, and downloads. The artwork loader also uses this client. BitChord leaves the Android VPN slot available to other apps.

microG runs as a separate Android application. Its account and consent requests follow its own connection settings. To route microG’s traffic as well, configure a device-level VPN that includes microG.

A running local proxy confirms that BitChord can connect to its listener. To verify remote tunnel connectivity, check the WireGuard peer handshake and traffic while streaming on the device. The embedded engine supports `arm64-v8a` and `x86_64`.

## Player controls inherited from the fork

- Tapping a song opens Now Playing by default. Turn off **Open full player when tapping a song** to start in the mini-player.
- Swipe the mini-player down to stop playback and clear the queue. A short or canceled drag returns it to its original position. Listen Together’s host control lock also applies to this gesture.
- The current track’s menu can remove its entry from the originating playlist when the user owns that playlist and BitChord knows the entry ID.

The Android integration originated in the [04pril fork](https://github.com/04pril/BitChord-Android/tree/feat/app-local-proxy). The JNI source and third-party licenses are available at the [pinned WireGuard revision](https://github.com/seo-rii/morphe-patches/tree/d03b7439f517eeed13d12edc1f922ee0e640cd13/native/wireguard).
