package com.cowork.bikerecoder.ride

/** The commands of a running recording that the ride screens send (implemented by [RideController]). */
interface RideControls {
    fun pause()

    /** Resumes a manual or an automatic pause. */
    fun resume()

    /** The auto-pause chip of this recording (the setting is only its default). */
    fun setAutoPause(enabled: Boolean)

    /** Stops the recording and ends the trip as [end] says (see [RideController.stopRecording]). */
    suspend fun stopRecording(end: RecordingEnd)

    /** No recording to command. */
    object None : RideControls {
        override fun pause() = Unit
        override fun resume() = Unit
        override fun setAutoPause(enabled: Boolean) = Unit
        override suspend fun stopRecording(end: RecordingEnd) = Unit
    }
}
