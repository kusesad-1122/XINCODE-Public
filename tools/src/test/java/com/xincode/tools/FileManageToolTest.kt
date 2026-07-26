package com.xincode.tools

import com.xincode.core.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * delete_file / make_directory 的护栏测试。
 *
 * delete_file 是这批工具里唯一**不可逆**的:写错文件还能重写,删错了就没了。
 * 所以它的每条护栏都要有测试压着 —— 尤其是「非空目录必须显式 recursive」和
 * 「工作区根不能删」这两条,它们正是出事时最贵的两种。
 *
 * 用真实临时目录跑,不 mock 文件系统:这些工具的价值全在与真实 File API 的交互上,
 * mock 掉就等于什么都没测。
 */
class FileManageToolTest {

    private lateinit var workspace: File
    private var savedRoot: String = ""

    @Before
    fun setUp() {
        workspace = Files.createTempDirectory("xincode-test").toFile()
        savedRoot = WorkspaceContext.workspaceRoot
        WorkspaceContext.workspaceRoot = workspace.absolutePath
    }

    @After
    fun tearDown() {
        WorkspaceContext.workspaceRoot = savedRoot
        workspace.deleteRecursively()
    }

    private fun file(name: String, content: String = "x"): File =
        File(workspace, name).apply { parentFile?.mkdirs(); writeText(content) }

    // ---- delete_file ----

    @Test
    fun deletesPlainFile() = runTest {
        val f = file("a.txt")
        val r = DeleteFileTool().execute(mapOf("path" to "a.txt"))
        assertTrue(r is ToolResult.Success)
        assertFalse("文件应当已被删除", f.exists())
    }

    @Test
    fun deletesEmptyDirectoryWithoutRecursive() = runTest {
        val d = File(workspace, "empty").apply { mkdirs() }
        val r = DeleteFileTool().execute(mapOf("path" to "empty"))
        assertTrue(r is ToolResult.Success)
        assertFalse(d.exists())
    }

    @Test
    fun refusesNonEmptyDirectoryWithoutRecursive() = runTest {
        file("dir/inner.txt")
        val r = DeleteFileTool().execute(mapOf("path" to "dir"))

        assertTrue("非空目录没给 recursive 就该拒绝", r is ToolResult.Error)
        assertTrue("错误信息要告诉模型怎么补救", (r as ToolResult.Error).message.contains("recursive"))
        assertTrue("拒绝之后文件必须还在", File(workspace, "dir/inner.txt").exists())
    }

    @Test
    fun deletesNonEmptyDirectoryWithRecursive() = runTest {
        file("dir/inner.txt")
        val r = DeleteFileTool().execute(mapOf("path" to "dir", "recursive" to "true"))
        assertTrue(r is ToolResult.Success)
        assertFalse(File(workspace, "dir").exists())
    }

    @Test
    fun refusesToDeleteWorkspaceRoot() = runTest {
        file("keep.txt")
        // 三种写法都指向根,都必须被挡住
        for (p in listOf(".", workspace.absolutePath, "${workspace.absolutePath}/")) {
            val r = DeleteFileTool().execute(mapOf("path" to p, "recursive" to "true"))
            assertTrue("路径 $p 指向工作区根,必须拒绝", r is ToolResult.Error)
        }
        assertTrue("工作区必须完好", File(workspace, "keep.txt").exists())
    }

    @Test
    fun reportsMissingPathInsteadOfSilentSuccess() = runTest {
        val r = DeleteFileTool().execute(mapOf("path" to "nope.txt"))
        assertTrue("删不存在的东西要明确报错,不能假装成功", r is ToolResult.Error)
    }

    @Test
    fun requiresPathParam() = runTest {
        val r = DeleteFileTool().execute(emptyMap())
        assertTrue(r is ToolResult.Error)
    }

    // ---- make_directory ----

    @Test
    fun createsNestedDirectories() = runTest {
        val r = MakeDirectoryTool().execute(mapOf("path" to "a/b/c"))
        assertTrue(r is ToolResult.Success)
        assertTrue(File(workspace, "a/b/c").isDirectory)
    }

    @Test
    fun isIdempotent() = runTest {
        MakeDirectoryTool().execute(mapOf("path" to "x"))
        // 第二次必须还是成功 —— 报错会让模型以为出事了然后开始绕路
        val r = MakeDirectoryTool().execute(mapOf("path" to "x"))
        assertTrue("重复建目录应当幂等成功", r is ToolResult.Success)
    }

    @Test
    fun refusesWhenFileWithSameNameExists() = runTest {
        file("clash")
        val r = MakeDirectoryTool().execute(mapOf("path" to "clash"))
        assertTrue("同名文件已存在时不能假装建成功", r is ToolResult.Error)
    }

    // ---- sleep ----

    @Test
    fun sleepClampsAndRejectsGarbage() = runTest {
        assertTrue(SleepTool().execute(mapOf("seconds" to "abc")) is ToolResult.Error)

        // 超过上限要被夹住而不是真睡那么久;runTest 的虚拟时钟让这一步不占实际时间
        val r = SleepTool().execute(mapOf("seconds" to "9999"))
        assertTrue(r is ToolResult.Success)
        assertEquals("已等待 30.0 秒", (r as ToolResult.Success).output)
    }
}
