package com.opspilot.storm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 只读视图（Ops Console）：在途组数与脱敏 key 列表，无副作用。 */
class SingleFlightRegistryTest {

    @Test
    void inFlightGroupsAndPeekTrackLeaderLifecycle() {
        var reg = new SingleFlightRegistry();
        assertEquals(0, reg.inFlightGroups());
        var r1 = reg.getOrCreate("tenant-a:fp1:3");
        var r2 = reg.getOrCreate("tenant-b:fp2:3");
        reg.getOrCreate("tenant-a:fp1:3");                      // follower 不新建组
        assertEquals(2, reg.inFlightGroups());
        assertTrue(reg.peekKeys(10).containsAll(java.util.List.of("tenant-a:fp1:3", "tenant-b:fp2:3")));
        assertEquals(1, reg.peekKeys(1).size(), "n 限流生效");

        reg.finish("tenant-a:fp1:3", r1.future());
        reg.finish("tenant-b:fp2:3", r2.future());
        assertEquals(0, reg.inFlightGroups(), "peek/finish 后计数归零（读不占位）");
    }
}
