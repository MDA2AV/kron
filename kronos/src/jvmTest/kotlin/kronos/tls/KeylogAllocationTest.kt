package kronos.tls

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class KeylogAllocationTest {
    // A String of the line, or of its hex, would show here: each is over 64 bytes.
    @Test
    fun `decoding a traffic secret allocates its own array and nothing else`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        withKeylogLine("CLIENT_TRAFFIC_SECRET_0 ${"a".repeat(64)} ${SECRET.toHexString()}") { line ->
            trafficSecret(line)   // the first call may load or initialise classes

            val before = threads.currentThreadAllocatedBytes
            val secret = trafficSecret(line)
            val allocated = threads.currentThreadAllocatedBytes - before

            // 32 bytes take a 48-byte array.
            assertTrue(secret != null && allocated <= SECRET.size + 32, "decoding a ${SECRET.size}-byte secret allocated $allocated bytes")
        }
    }
}
