package com.cowork.bikerecoder.core.video

/** 공유 카드 크기(픽셀, 스펙 §2.1): 게시물 1080×1350, 스토리 1080×1920, 스티커 1080×1080. 영상의 [Aspect]와는 별개다. */
enum class CardSize(val width: Int, val height: Int) {
    FEED(1080, 1350),
    STORY(1080, 1920),
    STICKER(1080, 1080),
}
