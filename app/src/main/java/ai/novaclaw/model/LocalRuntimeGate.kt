package ai.novaclaw.model

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One gate for the whole LiteRT runtime. PokeClaw synchronized only the engine
 * reference, then closed the engine while conversations were still live → native
 * use-after-free on GPU fallback. Here, engine creation, conversation creation,
 * and engine close are all serialized, and close is refused while leases exist.
 */
object LocalRuntimeGate {
    private val lock = ReentrantReadWriteLock()
    private var activeLeases = 0

    fun <T> read(block: () -> T): T = lock.read(block)
    fun <T> write(block: () -> T): T = lock.write(block)

    fun acquireLease() = lock.write { activeLeases++ }
    fun releaseLease() = lock.write { activeLeases = (activeLeases - 1).coerceAtLeast(0) }
    fun leaseCount(): Int = lock.read { activeLeases }
}
