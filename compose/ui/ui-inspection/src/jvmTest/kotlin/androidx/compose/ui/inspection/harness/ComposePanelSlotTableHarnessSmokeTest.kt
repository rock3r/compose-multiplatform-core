package androidx.compose.ui.inspection.harness

import androidx.compose.ui.inspection.JvmComposeMultiplatformLayoutInspector
import kotlin.test.Test
import kotlin.test.assertNotNull

class ComposePanelSlotTableHarnessSmokeTest {

    @Test
    fun attachDetachSnapshotPipelineDoesNotCrash() {
        val inspector = JvmComposeMultiplatformLayoutInspector()

        val layoutInspector = inspector.attachToCurrentProcess()
        val snapshots = layoutInspector.composableNodes.value

        assertNotNull(snapshots)
        layoutInspector.detach()
    }
}
