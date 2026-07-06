/*
 * Unit tests for [PolarBypassPureMath].
 *
 * These tests are pure — no Minecraft instance, no player, no mc. They
 * verify the mathematical correctness of the PolarBypass pipeline.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.math.abs
import kotlin.random.Random

class PolarBypassPureMathTest {

    // ── wrapDegrees ──────────────────────────────────────────────────

    @Test
    @DisplayName("wrapDegrees: 0 → 0")
    fun wrapDegrees_zero() {
        assertEquals(0f, PolarBypassPureMath.wrapDegrees(0f), 0.0001f)
    }

    @Test
    @DisplayName("wrapDegrees: 90 → 90")
    fun wrapDegrees_quarter() {
        assertEquals(90f, PolarBypassPureMath.wrapDegrees(90f), 0.0001f)
    }

    @Test
    @DisplayName("wrapDegrees: 180 → -180 (canonical edge)")
    fun wrapDegrees_half() {
        // 180° and -180° are the same direction; we canonicalize to -180.
        val result = PolarBypassPureMath.wrapDegrees(180f)
        assertTrue(result == -180f || result == 180f, "expected ±180, got $result")
    }

    @Test
    @DisplayName("wrapDegrees: 270 → -90")
    fun wrapDegrees_threeQuarters() {
        assertEquals(-90f, PolarBypassPureMath.wrapDegrees(270f), 0.0001f)
    }

    @Test
    @DisplayName("wrapDegrees: 360 → 0")
    fun wrapDegrees_full() {
        assertEquals(0f, PolarBypassPureMath.wrapDegrees(360f), 0.0001f)
    }

    @Test
    @DisplayName("wrapDegrees: 720 → 0 (multiple full turns)")
    fun wrapDegrees_multipleTurns() {
        assertEquals(0f, PolarBypassPureMath.wrapDegrees(720f), 0.0001f)
    }

    @Test
    @DisplayName("wrapDegrees: -90 → -90")
    fun wrapDegrees_negativeQuarter() {
        assertEquals(-90f, PolarBypassPureMath.wrapDegrees(-90f), 0.0001f)
    }

    @Test
    @DisplayName("wrapDegrees: -270 → 90")
    fun wrapDegrees_negativeThreeQuarters() {
        assertEquals(90f, PolarBypassPureMath.wrapDegrees(-270f), 0.0001f)
    }

    @Test
    @DisplayName("wrapDegrees: output always in [-180, 180]")
    fun wrapDegrees_alwaysInRange() {
        // Property-based: test 10 000 random inputs.
        val random = Random(42)
        repeat(10_000) {
            val input = random.nextFloat() * 10_000f - 5_000f
            val output = PolarBypassPureMath.wrapDegrees(input)
            assertTrue(output in -180f..180f, "input=$input → output=$output out of range")
        }
    }

    @Test
    @DisplayName("wrapDegrees: idempotent — wrap(wrap(x)) == wrap(x)")
    fun wrapDegrees_idempotent() {
        val random = Random(123)
        repeat(10_000) {
            val input = random.nextFloat() * 10_000f - 5_000f
            val once = PolarBypassPureMath.wrapDegrees(input)
            val twice = PolarBypassPureMath.wrapDegrees(once)
            assertEquals(once, twice, 0.0001f, "idempotence failed for input=$input")
        }
    }

    // ── clampDelta ───────────────────────────────────────────────────

    @Test
    @DisplayName("clampDelta: no clamp when delta within envelope")
    fun clampDelta_noClamp() {
        val current = Rotation(0f, 0f)
        val target = Rotation(10f, 5f)
        val result = PolarBypassPureMath.clampDelta(current, target, 20f, 15f)
        assertEquals(target.yaw, result.yaw, 0.0001f)
        assertEquals(target.pitch, result.pitch, 0.0001f)
    }

    @Test
    @DisplayName("clampDelta: clamps yaw when exceeding maxYawDelta")
    fun clampDelta_clampsYaw() {
        val current = Rotation(0f, 0f)
        val target = Rotation(50f, 0f)
        val result = PolarBypassPureMath.clampDelta(current, target, 20f, 15f)
        assertEquals(20f, result.yaw, 0.0001f)
        assertEquals(0f, result.pitch, 0.0001f)
    }

    @Test
    @DisplayName("clampDelta: clamps pitch when exceeding maxPitchDelta")
    fun clampDelta_clampsPitch() {
        val current = Rotation(0f, 0f)
        val target = Rotation(0f, 30f)
        val result = PolarBypassPureMath.clampDelta(current, target, 20f, 15f)
        assertEquals(0f, result.yaw, 0.0001f)
        assertEquals(15f, result.pitch, 0.0001f)
    }

    @Test
    @DisplayName("clampDelta: clamps both yaw and pitch")
    fun clampDelta_clampsBoth() {
        val current = Rotation(0f, 0f)
        val target = Rotation(50f, 30f)
        val result = PolarBypassPureMath.clampDelta(current, target, 20f, 15f)
        assertEquals(20f, result.yaw, 0.0001f)
        assertEquals(15f, result.pitch, 0.0001f)
    }

    @Test
    @DisplayName("clampDelta: wraps yaw across the ±180 boundary")
    fun clampDelta_wrapsYaw() {
        // current = 170, target = -170 → shortest delta is +20 (via +180)
        val current = Rotation(170f, 0f)
        val target = Rotation(-170f, 0f)
        val result = PolarBypassPureMath.clampDelta(current, target, 15f, 15f)
        // The clamp should move us +15° toward -170 via the +180 boundary,
        // landing at 170 + 15 = 185 → wrapped to -175.
        assertEquals(-175f, result.yaw, 0.01f)
    }

    @Test
    @DisplayName("clampDelta: negative delta clamps correctly")
    fun clampDelta_negativeDelta() {
        val current = Rotation(0f, 0f)
        val target = Rotation(-50f, -30f)
        val result = PolarBypassPureMath.clampDelta(current, target, 20f, 15f)
        assertEquals(-20f, result.yaw, 0.0001f)
        assertEquals(-15f, result.pitch, 0.0001f)
    }

    // ── clampEngages ─────────────────────────────────────────────────

    @Test
    @DisplayName("clampEngages: false when delta within envelope")
    fun clampEngages_falseWhenWithin() {
        val current = Rotation(0f, 0f)
        val target = Rotation(10f, 5f)
        assertFalse(PolarBypassPureMath.clampEngages(current, target, 20f, 15f))
    }

    @Test
    @DisplayName("clampEngages: true when yaw exceeds")
    fun clampEngages_trueWhenYawExceeds() {
        val current = Rotation(0f, 0f)
        val target = Rotation(50f, 0f)
        assertTrue(PolarBypassPureMath.clampEngages(current, target, 20f, 15f))
    }

    @Test
    @DisplayName("clampEngages: true when pitch exceeds")
    fun clampEngages_trueWhenPitchExceeds() {
        val current = Rotation(0f, 0f)
        val target = Rotation(0f, 30f)
        assertTrue(PolarBypassPureMath.clampEngages(current, target, 20f, 15f))
    }

    @Test
    @DisplayName("clampEngages: false at exact boundary (<=)")
    fun clampEngages_falseAtBoundary() {
        val current = Rotation(0f, 0f)
        val target = Rotation(20f, 15f)
        // delta == max → NOT exceeding (strict >), so clamp does NOT engage.
        assertFalse(PolarBypassPureMath.clampEngages(current, target, 20f, 15f))
    }

    // ── applyDrift ───────────────────────────────────────────────────

    @Test
    @DisplayName("applyDrift: amplitude=0 returns input unchanged")
    fun applyDrift_zeroAmplitudeIsNoop() {
        val rotation = Rotation(45f, -10f)
        val result = PolarBypassPureMath.applyDrift(rotation, 1.0f, 0f, 0.3f)
        assertEquals(rotation.yaw, result.yaw, 0.0001f)
        assertEquals(rotation.pitch, result.pitch, 0.0001f)
    }

    @Test
    @DisplayName("applyDrift: drift is bounded by amplitude")
    fun applyDrift_boundedByAmplitude() {
        val rotation = Rotation(0f, 0f)
        // Sample many time points — the drift should never exceed the
        // amplitude (yaw) or 0.6×amplitude (pitch).
        val amplitude = 0.4f
        repeat(1_000) { i ->
            val t = i * 0.1f
            val result = PolarBypassPureMath.applyDrift(rotation, t, amplitude, 0.3f)
            assertTrue(abs(result.yaw) <= amplitude + 0.0001f, "yaw drift out of bounds: ${result.yaw}")
            assertTrue(abs(result.pitch) <= amplitude * 0.6f + 0.0001f, "pitch drift out of bounds: ${result.pitch}")
        }
    }

    @Test
    @DisplayName("applyDrift: at t=0 yaw drift = amplitude (cos(0)=1)")
    fun applyDrift_atZeroTime() {
        val rotation = Rotation(0f, 0f)
        val amplitude = 0.5f
        val result = PolarBypassPureMath.applyDrift(rotation, 0f, amplitude, 0.3f)
        assertEquals(amplitude, result.yaw, 0.0001f)
        // pitch at t=0: sin(0) = 0
        assertEquals(0f, result.pitch, 0.0001f)
    }

    // ── gaussianNoise ────────────────────────────────────────────────

    @Test
    @DisplayName("gaussianNoise: mean ≈ 0 over 100 000 samples")
    fun gaussianNoise_meanApproximatelyZero() {
        val random = Random(42)
        val n = 100_000
        var sum = 0.0
        repeat(n) {
            sum += PolarBypassPureMath.gaussianNoise(random)
        }
        val mean = sum / n
        // For N(0,1), the standard error of the mean is 1/sqrt(N) ≈ 0.003.
        // We allow 5× that for safety.
        assertTrue(abs(mean) < 0.02, "mean=$mean should be ≈ 0")
    }

    @Test
    @DisplayName("gaussianNoise: stddev ≈ 1 over 100 000 samples")
    fun gaussianNoise_stddevApproximatelyOne() {
        val random = Random(99)
        val n = 100_000
        val samples = DoubleArray(n) { PolarBypassPureMath.gaussianNoise(random).toDouble() }
        val mean = samples.average()
        val variance = samples.map { (it - mean) * (it - mean) }.average()
        val stddev = kotlin.math.sqrt(variance)
        // Allow ±5% tolerance.
        assertTrue(stddev in 0.95..1.05, "stddev=$stddev should be ≈ 1")
    }

    @Test
    @DisplayName("gaussianNoise: no NaN or Infinity")
    fun gaussianNoise_noNaNOrInfinity() {
        val random = Random(0)
        repeat(100_000) {
            val z = PolarBypassPureMath.gaussianNoise(random)
            assertFalse(z.isNaN(), "NaN at iteration $it")
            assertFalse(z.isInfinite(), "Infinite at iteration $it")
        }
    }

    @Test
    @DisplayName("gaussianNoise: deterministic with same seed")
    fun gaussianNoise_deterministicWithSeed() {
        val r1 = Random(12345)
        val r2 = Random(12345)
        repeat(1_000) {
            assertEquals(PolarBypassPureMath.gaussianNoise(r1), PolarBypassPureMath.gaussianNoise(r2), 0.0f)
        }
    }

    // ── applyNoise ───────────────────────────────────────────────────

    @Test
    @DisplayName("applyNoise: stddev=0 returns input unchanged")
    fun applyNoise_zeroStddevIsNoop() {
        val rotation = Rotation(45f, -10f)
        val result = PolarBypassPureMath.applyNoise(rotation, 0f, Random(0))
        assertEquals(rotation.yaw, result.yaw, 0.0001f)
        assertEquals(rotation.pitch, result.pitch, 0.0001f)
    }

    @Test
    @DisplayName("applyNoise: noise magnitude bounded by ~5 stddev (99.9999% of samples)")
    fun applyNoise_boundedByFiveStddev() {
        val rotation = Rotation(0f, 0f)
        val stddev = 0.05f
        val random = Random(1)
        // 5σ covers 99.9999% of a gaussian — over 100 000 samples we expect
        // at most ~0.1 violations on average, so we allow a small slack.
        var violations = 0
        repeat(100_000) {
            val result = PolarBypassPureMath.applyNoise(rotation, stddev, random)
            if (abs(result.yaw) > 5f * stddev) violations++
            if (abs(result.pitch) > 5f * stddev) violations++
        }
        assertTrue(violations < 10, "too many 5σ violations: $violations")
    }

    // ── process (end-to-end pipeline) ────────────────────────────────

    @Test
    @DisplayName("process: no clamp when within envelope, drift+noise applied")
    fun process_noClamp() {
        val current = Rotation(0f, 0f)
        val target = Rotation(10f, 5f)
        val result = PolarBypassPureMath.process(
            current = current,
            target = target,
            tickSeconds = 1.0f,
            maxYawDelta = 20f,
            maxPitchDelta = 15f,
            noiseStddev = 0.0f,  // disable noise for deterministic test
            driftAmplitude = 0.0f,  // disable drift
            driftFrequency = 0.3f,
            random = Random(0)
        )
        assertFalse(result.clampEngaged)
        assertEquals(target.yaw, result.final.yaw, 0.001f)
        assertEquals(target.pitch, result.final.pitch, 0.001f)
    }

    @Test
    @DisplayName("process: clamp engages when delta exceeds envelope")
    fun process_clampEngages() {
        val current = Rotation(0f, 0f)
        val target = Rotation(50f, 30f)
        val result = PolarBypassPureMath.process(
            current = current,
            target = target,
            tickSeconds = 1.0f,
            maxYawDelta = 20f,
            maxPitchDelta = 15f,
            noiseStddev = 0.0f,
            driftAmplitude = 0.0f,
            driftFrequency = 0.3f,
            random = Random(0)
        )
        assertTrue(result.clampEngaged)
        // Without noise/drift, final should equal clamped target.
        assertEquals(20f, result.final.yaw, 0.001f)
        assertEquals(15f, result.final.pitch, 0.001f)
        // The clamped intermediate should also be available without a
        // second clampDelta call — this is the optimization that
        // prevents double work in [CustomAuraPolarBypass.process].
        assertEquals(20f, result.clamped.yaw, 0.001f)
        assertEquals(15f, result.clamped.pitch, 0.001f)
    }

    @Test
    @DisplayName("process: drift+noise do not push result far from clamped target")
    fun process_driftNoiseBounded() {
        val current = Rotation(0f, 0f)
        val target = Rotation(10f, 5f)
        val random = Random(2)
        // Over many time points, the final result should stay within
        // amplitude + 5*stddev of the clamped target.
        val amplitude = 0.4f
        val stddev = 0.05f
        repeat(1_000) { i ->
            val result = PolarBypassPureMath.process(
                current = current,
                target = target,
                tickSeconds = i * 0.1f,
                maxYawDelta = 20f,
                maxPitchDelta = 15f,
                noiseStddev = stddev,
                driftAmplitude = amplitude,
                driftFrequency = 0.3f,
                random = random
            )
            val yawDelta = abs(result.final.yaw - target.yaw)
            val pitchDelta = abs(result.final.pitch - target.pitch)
            assertTrue(yawDelta < amplitude + 5f * stddev + 0.01f, "yaw drift+noise too big: $yawDelta")
            assertTrue(pitchDelta < amplitude * 0.6f + 5f * stddev + 0.01f, "pitch drift+noise too big: $pitchDelta")
        }
    }

    @Test
    @DisplayName("process: pitch never exceeds vanilla [-90, 90] after drift+noise")
    fun process_pitchClampedToVanillaRange() {
        // Even with extreme drift amplitude and noise, pitch must stay
        // in [-90, 90] so client and server agree on the rotation.
        val current = Rotation(0f, 85f)  // already near the upper limit
        val target = Rotation(0f, 89f)
        val random = Random(7)
        repeat(10_000) { i ->
            val result = PolarBypassPureMath.process(
                current = current,
                target = target,
                tickSeconds = i * 0.1f,
                maxYawDelta = 30f,
                maxPitchDelta = 30f,
                noiseStddev = 0.5f,  // large noise
                driftAmplitude = 2.0f,  // large drift
                driftFrequency = 1.0f,
                random = random
            )
            assertTrue(result.final.pitch in -90f..90f,
                "pitch ${result.final.pitch} out of vanilla range at i=$i")
            assertTrue(result.clamped.pitch in -90f..90f,
                "clamped pitch ${result.clamped.pitch} out of vanilla range at i=$i")
        }
    }

    @Test
    @DisplayName("gaussianNoise: worst-case spike bounded by ~5.4σ (no 14σ outliers)")
    fun gaussianNoise_boundedSpike() {
        // The previous Float.MIN_VALUE floor allowed nextFloat()==0 to
        // produce a 14.4σ spike. With the new 1e-7 floor, the worst
        // case is sqrt(-2*ln(1e-7)) ≈ 5.4σ.
        //
        // We seed a Random that we manipulate to force u1=0 (the
        // pathological case) and verify the spike is bounded.
        val adversarialRandom = object : Random() {
            private var callCount = 0
            override fun nextFloat(): Float {
                callCount++
                // First call (u1 in Box-Muller) returns 0 to trigger
                // the floor; subsequent calls return a normal value.
                return if (callCount % 2 == 1) 0f else 0.5f
            }
            override fun nextBits(bitCount: Int): Int = 0
        }
        repeat(1_000) {
            val z = PolarBypassPureMath.gaussianNoise(adversarialRandom)
            // 5.4σ + small slack for floating-point error.
            assertTrue(abs(z) <= 6.0f, "gaussian spike $z exceeded 6σ bound")
            assertFalse(z.isNaN() || z.isInfinite(), "gaussian produced NaN/Inf")
        }
    }
}
