package com.cowork.bikerecoder.core.video

/** 이미지 위 사각형(픽셀, 왼쪽 위 기준). */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

/** 요약 이미지 배치. [stats]는 2줄×3칸(윗줄 왼→오, 아랫줄 왼→오), [photos]는 0~3장(왼→오). */
data class ImageLayout(val header: Box, val map: Box, val stats: List<Box>, val photos: List<Box>, val footer: Box)

/** 요약 이미지 배치(스펙 §4). 크기는 [Aspect]의 픽셀 크기. */
object SummaryImageLayout {

    const val MAX_PHOTOS = 3

    private const val MARGIN = 48f
    private const val GAP = 24f // 영역 사이
    private const val CELL_GAP = 16f // 칸 사이
    private const val STAT_ROW_H = 150f
    private const val FOOTER_H = 56f

    /** 세로: 지도 높이 = 화면의 절반. */
    private const val PORTRAIT_MAP_SHARE = 0.5f
    private const val PORTRAIT_HEADER_H = 168f
    private const val PORTRAIT_PHOTO_H = 240f

    /** 가로: 왼쪽 60%가 지도. */
    private const val LANDSCAPE_MAP_SHARE = 0.6f
    private const val LANDSCAPE_HEADER_H = 140f

    /**
     * 세로: 머리 → 지도(폭 전체, 높이 절반) → 숫자 2줄×3칸 → 사진 줄(있을 때만) → 바닥 "bikeRecoder". 사진이 없으면 사진 줄의
     * 높이만큼 지도가 길어진다(바닥 위에 빈 띠를 남기지 않는다).
     * 가로: 왼쪽 60% 지도(높이 전체), 오른쪽 40%에 머리·숫자·사진 줄을 바닥 위 공간의 세로 가운데에, 맨 아래 바닥.
     * [photoCount]는 0~3으로 자른다.
     */
    fun layout(aspect: Aspect, photoCount: Int): ImageLayout {
        val w = aspect.width.toFloat()
        val h = aspect.height.toFloat()
        val photos = photoCount.coerceIn(0, MAX_PHOTOS)
        return when (aspect) {
            Aspect.PORTRAIT -> {
                val contentW = w - 2 * MARGIN
                val header = Box(MARGIN, MARGIN, contentW, PORTRAIT_HEADER_H)
                // No photo row: its height (and its gap) goes to the map.
                val extra = if (photos == 0) PORTRAIT_PHOTO_H + GAP else 0f
                val map = Box(0f, header.bottom + GAP, w, h * PORTRAIT_MAP_SHARE + extra)
                val stats = statGrid(MARGIN, map.bottom + GAP, contentW)
                val photoRow = photoRow(MARGIN, stats.last().bottom + GAP, contentW, PORTRAIT_PHOTO_H, photos)
                ImageLayout(header, map, stats, photoRow, Box(MARGIN, h - MARGIN - FOOTER_H, contentW, FOOTER_H))
            }
            Aspect.LANDSCAPE -> {
                val map = Box(0f, 0f, w * LANDSCAPE_MAP_SHARE, h)
                val left = map.x + map.w + MARGIN
                val contentW = w - left - MARGIN
                val footer = Box(left, h - MARGIN - FOOTER_H, contentW, FOOTER_H)
                val photoH = cellWidth(contentW)
                // Header … last row as one block, centred between the top margin and the footer (keeping GAP above it).
                val block = LANDSCAPE_HEADER_H + GAP + 2 * STAT_ROW_H + CELL_GAP + (if (photos > 0) GAP + photoH else 0f)
                val room = footer.y - GAP - MARGIN
                val top = MARGIN + ((room - block) / 2).coerceAtLeast(0f)
                val header = Box(left, top, contentW, LANDSCAPE_HEADER_H)
                val stats = statGrid(left, header.bottom + GAP, contentW)
                val photoRow = photoRow(left, stats.last().bottom + GAP, contentW, photoH, photos)
                ImageLayout(header, map, stats, photoRow, footer)
            }
        }
    }

    private val Box.bottom get() = y + h

    private fun cellWidth(contentW: Float) = (contentW - 2 * CELL_GAP) / 3

    private fun statGrid(x: Float, top: Float, contentW: Float): List<Box> {
        val cw = cellWidth(contentW)
        return List(6) { i ->
            val row = i / 3
            val col = i % 3
            Box(x + col * (cw + CELL_GAP), top + row * (STAT_ROW_H + CELL_GAP), cw, STAT_ROW_H)
        }
    }

    /** 칸 폭은 3장일 때와 같고, 줄은 가운데 정렬. */
    private fun photoRow(x: Float, top: Float, contentW: Float, height: Float, count: Int): List<Box> {
        if (count == 0) return emptyList()
        val cw = cellWidth(contentW)
        val start = x + (contentW - (count * cw + (count - 1) * CELL_GAP)) / 2
        return List(count) { Box(start + it * (cw + CELL_GAP), top, cw, height) }
    }
}
