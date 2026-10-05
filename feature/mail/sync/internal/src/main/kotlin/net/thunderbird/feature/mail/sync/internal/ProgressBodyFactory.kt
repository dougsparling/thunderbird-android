package net.thunderbird.feature.mail.sync.internal

import com.fsck.k9.mail.DefaultBodyFactory
import java.io.InputStream
import java.io.OutputStream
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate
import org.apache.commons.io.output.CountingOutputStream

private const val PROGRESS_INTERVAL_MILLIS = 50L

/** Reports how many bytes of a body were downloaded so far, every 50 ms while it's being downloaded. */
internal class ProgressBodyFactory(private val onProgress: (Int) -> Unit) : DefaultBodyFactory() {
    override fun copyData(inputStream: InputStream, outputStream: OutputStream) {
        val timer = Timer()
        try {
            CountingOutputStream(outputStream).use { countingOutputStream ->
                timer.scheduleAtFixedRate(0, PROGRESS_INTERVAL_MILLIS) {
                    onProgress(countingOutputStream.count)
                }

                super.copyData(inputStream, countingOutputStream)
            }
        } finally {
            timer.cancel()
        }
    }
}
