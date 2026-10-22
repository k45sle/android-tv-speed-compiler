package dev.speedcompiler.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.working.JobView
import dev.speedcompiler.tv.ui.working.WorkingContent
import dev.speedcompiler.tv.ui.working.WorkingState

/** Working screen with a job that never finishes, so the arcade can be tried with a D-pad on an unpaired emulator. */
class DebugWorkingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpeedTheme { WorkingContent(WorkingState("Debug app", null, JobView.Running, 0, 120_000), ::finish) } }
    }
}
