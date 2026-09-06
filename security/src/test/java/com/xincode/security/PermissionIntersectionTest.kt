package com.xincode.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移植自 Codex permission_profile_intersection 语义的回归单测。
 * 跑法:`./gradlew :security:testDebugUnitTest`。
 */
class PermissionIntersectionTest {

    @Test
    fun networkIsAnd() {
        val open = PermissionProfile.unrestricted()
        val closed = PermissionProfile(FsPolicy.unrestricted(), NetPolicy.RESTRICTED)
        assertEquals(
            NetPolicy.ENABLED,
            (intersectProfiles(open, open) as IntersectionResult.Ok).profile.net
        )
        assertEquals(
            NetPolicy.RESTRICTED,
            (intersectProfiles(open, closed) as IntersectionResult.Ok).profile.net
        )
        assertEquals(
            NetPolicy.RESTRICTED,
            (intersectProfiles(closed, open) as IntersectionResult.Ok).profile.net
        )
    }

    @Test
    fun unrestrictedSideYieldsToOther() {
        val ws = PermissionProfile.workspace("/sdcard/xincode")
        val got = intersectProfiles(PermissionProfile.unrestricted(), ws) as IntersectionResult.Ok
        assertEquals(ws.fs, got.profile.fs)
        // 网络仍取 AND:一边开一边关 → 关。
        assertEquals(NetPolicy.RESTRICTED, got.profile.net)
    }

    @Test
    fun equalPoliciesPassThrough() {
        val ws = PermissionProfile.workspace("/sdcard/xincode")
        val got = intersectProfiles(ws, ws) as IntersectionResult.Ok
        assertEquals(ws, got.profile)
    }

    @Test
    fun grantsIntersectToCommonGround() {
        val authority = PermissionProfile(
            FsPolicy.restricted(FsGrant("/work", FsAccess.READ_WRITE)),
            NetPolicy.RESTRICTED
        )
        // 请求子目录写:交集 = 子目录写(同时满足两边)。
        val reqSub = PermissionProfile(
            FsPolicy.restricted(FsGrant("/work/proj", FsAccess.READ_WRITE)),
            NetPolicy.RESTRICTED
        )
        val sub = intersectProfiles(authority, reqSub) as IntersectionResult.Ok
        assertEquals(listOf(FsGrant("/work/proj", FsAccess.READ_WRITE)), sub.profile.fs.grants)

        // 请求读全量:写被压成读( min 约束)。
        val reqRead = PermissionProfile(
            FsPolicy.restricted(FsGrant("/work", FsAccess.READ)),
            NetPolicy.RESTRICTED
        )
        val read = intersectProfiles(authority, reqRead) as IntersectionResult.Ok
        assertEquals(listOf(FsGrant("/work", FsAccess.READ)), read.profile.fs.grants)

        // 请求不相交路径:交集为空 = 默认拒绝。
        val reqElse = PermissionProfile(
            FsPolicy.restricted(FsGrant("/other", FsAccess.READ_WRITE)),
            NetPolicy.RESTRICTED
        )
        val empty = intersectProfiles(authority, reqElse) as IntersectionResult.Ok
        assertTrue(empty.profile.fs.grants.isEmpty())
        assertFalse(empty.profile.allows("/other/x", write = false))
    }

    @Test
    fun allowsRespectsBoundary() {
        val ws = PermissionProfile.workspace("/work")
        assertTrue(ws.allows("/work/a.txt", write = true))
        assertTrue(ws.allows("/work", write = false))
        // /workbc 不是 /work 的子路径。
        assertFalse(ws.allows("/workbc", write = false))
        assertFalse(ws.allows("/etc/passwd", write = false))
        val ro = PermissionProfile.readOnlyWorkspace("/work")
        assertTrue(ro.allows("/work/a.txt", write = false))
        assertFalse(ro.allows("/work/a.txt", write = true))
    }

    @Test
    fun traversalRejected() {
        try {
            normalizeFsPath("/work/../../etc")
            throw AssertionError("越界必须拒")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("/work/a", normalizeFsPath("/work/./sub/../a"))
    }
}
