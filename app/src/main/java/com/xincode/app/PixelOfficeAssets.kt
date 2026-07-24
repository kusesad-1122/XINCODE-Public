package com.xincode.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 像素办公室素材加载器(指挥室场景用)。
 *
 * 素材来自 pixel-agents(MIT):地板/家具为其开源资源;角色 sprite 基于 JIK-A-4「Metro City」
 * 免费 topdown 角色包(见 app/src/main/assets/pixel/ATTRIBUTION)。仅本地渲染,非商用。
 *
 * 角色表:112×96 = 7列 × 3行;帧 16×32;行序 0=down,1=up,2=right。
 */
object PixelOfficeAssets {
    const val CHAR_W = 16
    const val CHAR_H = 32
    const val FRAMES_PER_ROW = 7
    const val DIR_DOWN = 0
    const val DIR_UP = 1
    const val DIR_RIGHT = 2

    class Loaded(
        val chars: List<ImageBitmap>,   // char_0..5 整表
        val floor: ImageBitmap?,        // 16×16
        val pcOn: List<ImageBitmap>,    // ON_1/2/3 (16×32)
        val pcOff: ImageBitmap?,        // 16×32
        val desk: ImageBitmap?,         // 32×32
        val carpet: ImageBitmap?,       // 64×64
        val decor: Map<String, ImageBitmap>  // 家具装饰:书架/绿植/时钟/白板/画/沙发/仙人掌…
    ) {
        val ok: Boolean get() = chars.isNotEmpty() && floor != null
        fun d(name: String): ImageBitmap? = decor[name]
    }

    @Volatile private var cached: Loaded? = null

    fun load(context: Context): Loaded {
        cached?.let { return it }
        val opts = BitmapFactory.Options().apply { inScaled = false } // 保留原始像素,不按屏幕密度缩放
        fun bmp(path: String): ImageBitmap? = try {
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
        } catch (_: Exception) { null }
        // 家具装饰(缺哪个跳过哪个)。
        val decorNames = listOf(
            "BOOKSHELF", "DOUBLE_BOOKSHELF", "LARGE_PLANT", "PLANT", "CLOCK",
            "WHITEBOARD", "SMALL_PAINTING_2", "CACTUS", "BIN",
            "SOFA_FRONT", "SOFA_BACK", "SOFA_SIDE", "COFFEE_TABLE"
        )
        val decor = HashMap<String, ImageBitmap>()
        for (nm in decorNames) bmp("pixel/decor/$nm.png")?.let { decor[nm] = it }
        val loaded = Loaded(
            chars = (0..5).mapNotNull { bmp("pixel/characters/char_$it.png") },
            floor = bmp("pixel/floors/floor_0.png"),
            pcOn = listOf("PC_FRONT_ON_1", "PC_FRONT_ON_2", "PC_FRONT_ON_3").mapNotNull { bmp("pixel/furniture/$it.png") },
            pcOff = bmp("pixel/furniture/PC_FRONT_OFF.png"),
            desk = bmp("pixel/furniture/SMALL_TABLE_FRONT.png"),
            carpet = bmp("pixel/carpets/carpet_0.png"),
            decor = decor
        )
        cached = loaded
        return loaded
    }
}
