package dev.varch.controller.ui

/** Everything the remote's screens can ask for. Implemented by the view model. */
interface RemoteActions {
    fun send(action: String, value: Double = 0.0, text: String = "")
    fun setVolume(level: Float)
    fun setBrightness(level: Float)
    fun movePointer(dx: Float, dy: Float)
    fun scrollPointer(dx: Float, dy: Float)
    fun sendClipboard(text: String)
    fun fetchClipboard()
    fun share(text: String)
    fun capture()
    fun setAlerts(enabled: Boolean)
    fun setSlides(enabled: Boolean)
    fun setMatchTheme(enabled: Boolean)
    fun setWatching(enabled: Boolean)
    fun unpair()
}
