package dev.ironlog.app.healthconnect

import android.content.Context

/**
 * Real Health Connect gateway STUB. The actual implementation is blocked by toolchain:
 *   connect-client:1.1.0-rc01 requires compileSdk 36 + AGP 8.9.1 (current: sdk 35 / AGP 8.5.2).
 *
 * To wire the real impl when the toolchain is upgraded:
 *   1. Uncomment the HC dependency in build.gradle.kts.
 *   2. Replace this stub with the full implementation (see git history or the archived plan).
 *   3. Remove the NoOpHealthConnectGateway fallback in MainActivity.
 *
 * Until then, the NoOpHealthConnectGateway is used for both installed and un-installed HC.
 * HC permissions in the manifest are still declared so they can be granted when the time comes.
 */
class RealHealthConnectGateway(@Suppress("unused") context: Context) : HealthConnectGateway by NoOpHealthConnectGateway()
