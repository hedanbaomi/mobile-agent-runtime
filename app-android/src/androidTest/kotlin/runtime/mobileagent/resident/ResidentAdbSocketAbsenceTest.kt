// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.resident

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.IOException
import java.net.SocketException
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verify the real platform exception chain, not a constructed ErrnoException fixture. */
@RunWith(AndroidJUnit4::class)
class ResidentAdbSocketAbsenceTest {
    @Test fun missingAbstractSocketProducesVerifiableKernelAbsence() {
        LocalSocket().use { socket ->
            try {
                socket.connect(LocalSocketAddress("resident-absent-test-${UUID.randomUUID()}", LocalSocketAddress.Namespace.ABSTRACT))
                fail("Random absent abstract endpoint connected")
            } catch (failure: Exception) {
                // This fixture contains only a generated public abstract-socket name; include
                // its native exception shape so a failed platform boundary is diagnosable.
                val diagnostic = generateSequence<Throwable>(failure) { it.cause }
                    .take(4).joinToString(" -> ") { "${it.javaClass.name}:${it.message?.take(256)}" }
                assertEquals("Platform must preserve kernel absence: $diagnostic", ResidentAdbRegistry.Publication.ABSENT,
                    ResidentAdbRegistry.classifyConnectionFailure(failure))
            }
        }
    }

    @Test fun nativeTranslationRejectsWrongShapesAndUnrelatedErrors() {
        assertEquals(ResidentAdbRegistry.Publication.ABSENT,
            ResidentAdbRegistry.classifyConnectionFailure(IOException(Os.strerror(OsConstants.ECONNREFUSED))))
        assertEquals(ResidentAdbRegistry.Publication.ABSENT,
            ResidentAdbRegistry.classifyConnectionFailure(IOException(Os.strerror(OsConstants.ENOENT))))
        for (failure in listOf(
            IOException("prefix " + Os.strerror(OsConstants.ECONNREFUSED)),
            IOException(Os.strerror(OsConstants.ECONNREFUSED) + "\n"),
            IOException(Os.strerror(OsConstants.EPERM)),
            IOException(Os.strerror(OsConstants.ECONNREFUSED), SecurityException("untrusted cause")),
            SocketException(Os.strerror(OsConstants.ECONNREFUSED)),
            SecurityException(Os.strerror(OsConstants.ECONNREFUSED)),
            ErrnoException("connect", OsConstants.EACCES),
        )) {
            assertEquals("Unproven connection failure must preserve recovery", ResidentAdbRegistry.Publication.UNKNOWN,
                ResidentAdbRegistry.classifyConnectionFailure(failure))
        }
    }
}
