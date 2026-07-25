package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * `current_time` —— 读取设备当前时间(纯本地,零依赖)。
 *
 * 为什么需要:模型自身没有时钟。不给它准确时间,它就只能靠训练数据猜——典型翻车是把 UTC
 * 当成本地时间(如本地 11:33 被说成 03:33),进而把定时任务的时间点算错。
 *
 * 为什么不靠网络授时:网络授时(worldtimeapi 等)在无网/被墙/环境未部署时会直接失败,
 * 而设备时钟总是可读。本工具不联网、不需要 root、不需要内置 Ubuntu 环境,毫秒返回。
 */
class CurrentTimeTool : Tool {

    override val name = "current_time"

    override val description =
        "获取设备当前的准确日期时间(本地时区,可选指定时区)。不联网、不需要 root,立即返回。" +
        "凡是涉及\"现在/今天/几点\"、设定定时任务时间点、计算时间差的场景,都先调用本工具拿准确时间,不要凭记忆推算。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("timezone", JSONObject().apply {
                put("type", "string")
                put("description", "可选。IANA 时区名,如 Asia/Shanghai、UTC。省略则用设备本地时区。")
            })
        })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return try {
            val now = Date()
            val tzArg = params["timezone"]?.trim().orEmpty()
            // 注意:TimeZone.getTimeZone() 对无法识别的 ID 会静默返回 GMT,会悄悄给出错误时间。
            // 这里显式校验,宁可报错让模型改正,也不返回一个看起来正常的错时间。
            val tz = if (tzArg.isBlank()) {
                TimeZone.getDefault()
            } else {
                val known = TimeZone.getAvailableIDs().any { it.equals(tzArg, ignoreCase = true) }
                if (!known && !tzArg.equals("UTC", ignoreCase = true) && !tzArg.equals("GMT", ignoreCase = true)) {
                    return ToolResult.Error("无法识别的时区 '$tzArg'。请用 IANA 时区名(如 Asia/Shanghai / UTC),或省略该参数用设备本地时区。")
                }
                TimeZone.getTimeZone(tzArg)
            }

            val human = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA).apply { timeZone = tz }
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = tz }
            val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            val offMin = tz.getOffset(now.time) / 60000
            val sign = if (offMin < 0) "-" else "+"
            val abs = kotlin.math.abs(offMin)
            val offStr = String.format(Locale.US, "UTC%s%02d:%02d", sign, abs / 60, abs % 60)

            ToolResult.Success(
                buildString {
                    append("当前时间:").append(human.format(now)).append("\n")
                    append("时区:").append(tz.id).append(" (").append(offStr).append(")\n")
                    append("ISO8601:").append(iso.format(now)).append("\n")
                    append("UTC:").append(utc.format(now)).append(" UTC\n")
                    append("Unix 毫秒:").append(now.time)
                }
            )
        } catch (t: Throwable) {
            ToolResult.Error("读取当前时间失败: ${t.message}")
        }
    }
}
