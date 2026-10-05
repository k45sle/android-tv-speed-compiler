# Wireless debugging and ADB

The Docker image includes ADB. You only need Docker on your computer. If you run the Python service directly, install Android platform-tools in that environment and make `adb` available on its PATH. The dashboard reports whether the service can find ADB; it does not download tools onto your computer.

Saved pairing keys let the service reconnect while Wireless debugging is enabled. Discovery can find a changed connection port. If discovery fails, enter the current connection IP:port under Manage TV details. If the TV turns debugging off, enable it on the TV before reconnecting. A disconnected server cannot send a command to turn it back on.

## Choose a connection method

| Method | Benefits | Limits |
| --- | --- | --- |
| Encrypted Wireless debugging | Android encrypts the ADB connection. Uses Android's built-in pairing screen. | The connection port can change. If the TV disables debugging, turn it back on; a manual connection address may be needed when discovery fails. |
| Traditional ADB on a fixed port | A predictable address is easier to reconnect to. A compatible TV companion can enable it after boot. | Traffic is unencrypted. Boot recovery requires a TV app or firmware support. The service's ADB key must still be authorized by the TV. |

Use a fixed port only on a trusted home network without port forwarding. Both methods give an authorized ADB client broad access to the TV. A public source repository does not mean the dashboard or ADB port should be publicly reachable.

## Optional fixed-port companion

[adb-auto-enable](https://github.com/mouldybread/adb-auto-enable) is a separate Android app installed on the TV. Its boot service enables Wireless debugging, disables automatic expiration of ADB authorizations, and switches ADB to a chosen fixed port, usually 5555. It needs its own setup and permissions. Its behavior on your TV has not been tested by this project.

Traditional ADB TCP/IP uses an unencrypted connection, whereas Android's Wireless debugging uses TLS. A fixed port alone does not ensure that debugging survives a restart. The companion's on-TV boot service supplies that part. See [Android's ADB architecture](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md) and the companion's [boot-service source](https://github.com/mouldybread/adb-auto-enable/blob/main/app/src/main/java/com/tpn/adbautoenable/AdbConfigService.java).

To use the companion:

1. Read its requirements and install an APK from its [official releases](https://github.com/mouldybread/adb-auto-enable/releases) on your TV. Open it once on the TV.
2. Follow its setup page to pair the companion with the TV and choose a target port. Its default is 5555. The companion has its own ADB key; this does not authorize Speed Compiler's key.
3. Enable the target port using the companion's controls. In Speed Compiler, choose the fixed-port connection method and enter the TV's address and chosen port.
4. Approve any ADB authorization prompt on the TV for this service, then retry the connection. Check that the intended TV is identified and shown as connected.
5. Restart the TV and check reconnection. Firmware restrictions, blocked boot receivers, or missing companion permissions can still prevent recovery. Keep the TV app installed and use its own troubleshooting guide for boot problems.

Speed Compiler does not install the companion or automatically switch a TV to traditional TCP/IP mode. A manually entered endpoint uses the ADB mode provided by the TV; entering a traditional TCP/IP endpoint does not make that connection encrypted. The selected method describes the setup you chose, not a separate audit of the network protocol.
