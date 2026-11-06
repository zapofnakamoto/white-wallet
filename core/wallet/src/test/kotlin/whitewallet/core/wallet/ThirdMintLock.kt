package whitewallet.core.wallet

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** One test at a time on the third mint, across the test JVMs: [Regtest.rotateMint3] restarts it under anything else using it. */
internal class ThirdMintLock : BeforeEachCallback, AfterEachCallback {
    private var channel: FileChannel? = null

    override fun beforeEach(context: ExtensionContext) {
        channel = FileChannel.open(File("build/third-mint.lock").toPath(), CREATE, WRITE).also { it.lock() }
    }

    override fun afterEach(context: ExtensionContext) {
        channel?.close()
    }
}
