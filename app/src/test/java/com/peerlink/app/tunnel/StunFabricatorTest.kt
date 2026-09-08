package com.peerlink.app.tunnel

import org.junit.Test

class StunFabricatorTest {
    @Test fun packetResponseRegression() { StunFabricatorChecks.verifyAll() }
}
