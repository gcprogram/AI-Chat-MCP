package app.aichatmcp

import android.content.Intent
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aichatmcp.clock.DeviceClock
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uhr-App des Geräts: Die AlarmClock-Intents finden eine Uhr-App (Sichtbarkeit über `<queries>` im Manifest), und ein Timer wird ohne
 * Fehler übergeben. Einen Wecker stellt der Test bewusst nicht (wiederholte Läufe würden Wecker anhäufen); er gehört auf den Emulator.
 */
@RunWith(AndroidJUnit4::class)
class ClockOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun clockAppIsVisibleForAlarmAndTimerIntents() {
        for (action in listOf(AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SET_TIMER)) {
            assertNotNull(action, Intent(action).resolveActivity(context.packageManager))
        }
    }

    @Test
    fun timerIsHandedToTheClockApp() {
        DeviceClock(context).setTimer(3600, "AIChat-Test") // wirft nichts, wenn die Uhr-App den Intent annimmt
    }
}
