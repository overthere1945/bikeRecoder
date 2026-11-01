package com.cowork.bikerecoder.core.video

/**
 * 카드 위 영역(픽셀). [top]·[bottom]은 반투명 띠, [map]은 경로를 맞출 곳(위성 카드: 두 띠 사이, 사진 카드: 아래 띠 안의 작은 경로 그림 칸).
 * 사진 카드는 위 띠가 없어서 [top]의 높이가 0이다.
 */
data class CardBoxes(val top: Box, val bottom: Box, val map: Box)

/** 공유 카드 배치(스펙 §2.2·§2.3)와 사진 장 배치(스펙 §2.4). 위치는 [CardSize]의 픽셀 크기 기준이다. */
object ShareCardLayout {

    /** 스토리: 위·아래 이만큼(화면 높이 비율)에는 글자를 두지 않는다(스펙 §2.2). */
    const val STORY_SAFE = 0.14f

    /** 가운데 영역(위성 카드 지도 칸)을 두 띠에서 사방으로 더 빼는 비율(가로는 너비, 세로는 높이 기준). */
    const val MAP_INSET = 0.06f

    private const val FEED_TOP = 0.13f
    private const val FEED_BOTTOM = 0.24f
    private const val STORY_TOP = 0.09f
    private const val STORY_BOTTOM = 0.20f

    /** 사진 카드의 작은 경로 그림: 아래 띠 높이의 60% 정사각(스펙 §2.3). */
    private const val MINI_MAP_SHARE = 0.6f

    /** 위성 카드(스펙 §2.2). [size]는 [CardSize.FEED] 또는 [CardSize.STORY]. */
    fun satellite(size: CardSize): CardBoxes {
        val (top, bottom) = bands(size)
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val x = w * MAP_INSET
        val y = top.y + top.h + h * MAP_INSET
        val map = Box(x, y, w - 2 * x, bottom.y - h * MAP_INSET - y)
        return CardBoxes(top, bottom, map)
    }

    /**
     * 사진 카드(스펙 §2.3): 아래 띠는 위성 카드와 같고, [CardBoxes.map]은 그 띠 안의 왼쪽 경로 그림 정사각(띠 높이의 60%,
     * 왼쪽 여백 = 위아래 여백). 위 띠는 없어 높이 0인 [CardBoxes.top]이 글자 없는 구역 가장자리에 놓인다.
     */
    fun photoCard(size: CardSize): CardBoxes {
        val (top, bottom) = bands(size)
        val side = bottom.h * MINI_MAP_SHARE
        val margin = (bottom.h - side) / 2
        return CardBoxes(top.copy(h = 0f), bottom, Box(bottom.x + margin, bottom.y + margin, side, side))
    }

    /** 원본 [srcW]×[srcH]를 [dstW]×[dstH]에 가득 채울 때 쓰는 원본 쪽 자를 사각형(원본 픽셀, 가운데 기준 자르기). */
    fun cover(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Box {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) { "크기 ${srcW}x$srcH → ${dstW}x$dstH" }
        val sw = srcW.toFloat()
        val sh = srcH.toFloat()
        // Compare aspect ratios by cross-multiplying (no division rounding).
        return if (srcW.toLong() * dstH > dstW.toLong() * srcH) {
            val w = sh * dstW / dstH
            Box((sw - w) / 2, 0f, w, sh)
        } else {
            val h = sw * dstH / dstW
            Box(0f, (sh - h) / 2, sw, h)
        }
    }

    /** 원본 [srcW]×[srcH]를 너비 [dstW]에 맞춰 [dstW]×[dstH] 안 가운데에 놓을 때의 도착 쪽 사각형(도착 픽셀). */
    fun fitWidth(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Box {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) { "크기 ${srcW}x$srcH → ${dstW}x$dstH" }
        val h = srcH.toFloat() * dstW / srcW
        return Box(0f, (dstH - h) / 2, dstW.toFloat(), h)
    }

    /** 위 띠와 아래 띠. 스토리는 안전 구역 안쪽에 둔다(위는 14%에서 시작, 아래는 86%에서 끝). */
    private fun bands(size: CardSize): Pair<Box, Box> {
        require(size != CardSize.STICKER) { "스티커에는 띠가 없다" }
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val (topShare, bottomShare, topY, bottomEnd) = when (size) {
            CardSize.STORY -> Band(STORY_TOP, STORY_BOTTOM, STORY_SAFE, 1 - STORY_SAFE)
            else -> Band(FEED_TOP, FEED_BOTTOM, 0f, 1f)
        }
        return Box(0f, h * topY, w, h * topShare) to Box(0f, h * (bottomEnd - bottomShare), w, h * bottomShare)
    }

    private data class Band(val topShare: Float, val bottomShare: Float, val topY: Float, val bottomEnd: Float)
}
