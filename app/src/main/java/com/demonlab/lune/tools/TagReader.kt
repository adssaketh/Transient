package com.demonlab.lune.tools

import java.io.File
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO

/**
 * One jaudiotagger read at a time.
 *
 * Reading a WAV's ID3 chunk loads its whole embedded cover picture into memory (up to tens of MB). The library scan and
 * the cover lookups used to do that in parallel; in the logs the app then sat at ~450 MB of memory and the main thread
 * waited on garbage collection until Android reported "not responding". Taking turns keeps the peak to a single file.
 */
object TagReader {
    private val lock = Any()

    fun read(file: File): AudioFile = synchronized(lock) { AudioFileIO.read(file) }
}
