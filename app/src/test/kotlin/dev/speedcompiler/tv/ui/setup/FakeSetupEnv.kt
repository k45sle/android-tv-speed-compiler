package dev.speedcompiler.tv.ui.setup


/** Test double. [hook] sees each call name so a test can flip the state a real call would change. */
class FakeSetupEnv(
    var developerOptions: Boolean = false,
    var wireless: Boolean = false,
    var apps: List<SetupApp> = emptyList(),
    var adbDebugging: Boolean = false,
    var portOpen: Boolean = false,
) : SetupEnv {
    val calls = mutableListOf<String>()
    var hook: (String) -> Unit = {}
    private fun call(name: String) { calls += name; hook(name) }

    override fun developerOptionsOn() = developerOptions
    override fun wirelessDebuggingOn() = wireless
    override fun adbDebuggingOn() = adbDebugging
    override suspend fun legacyPortOpen() = portOpen
    override fun openSettings() = call("openSettings")
    override fun openDeveloperOptions() = call("openDeveloperOptions")
    override fun startPairing() = call("startPairing")
    override fun stopPairing() = call("stopPairing")
    override fun reconnect() = call("reconnect")
    override suspend fun recentApps() = apps
}
