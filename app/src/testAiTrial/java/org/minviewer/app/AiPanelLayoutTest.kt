package org.minviewer.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPanelLayoutTest {
    @Test fun narrowLargeTextUsesTheFullAreaAndCompactControls() {
        assertEquals(AiPanelLayout(false,true,true),aiPanelLayout(320f,460f,2f,true,false))
        assertEquals(AiPanelLayout(false,false,true),aiPanelLayout(320f,460f,1f,true,false))
    }
    @Test fun shortLandscapeKeepsSideLayoutUnlessLargeTextNeedsFullArea() {
        assertEquals(AiPanelLayout(true,false,true),aiPanelLayout(600f,220f,1f,true,false))
        assertEquals(AiPanelLayout(true,true,true),aiPanelLayout(600f,220f,2f,true,false))
    }
    @Test fun tabletLargeTextAndClosedPanelDoNotHideTheDocument() {
        assertEquals(AiPanelLayout(true,false,true),aiPanelLayout(768f,900f,2f,true,false))
        assertEquals(AiPanelLayout(false,false,true),aiPanelLayout(320f,460f,2f,false,false))
    }
}
