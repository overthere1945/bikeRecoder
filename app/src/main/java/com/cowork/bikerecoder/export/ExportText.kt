package com.cowork.bikerecoder.export

/** Every user-visible text of the GPX/KML export (V1.7.0 spec §5: menu, recording, saved dialog, errors). */
object ExportText {
    /** The [⋮] button's spoken name. */
    const val MORE = "더 보기"
    const val EXPORT_GPX = "GPX로 내보내기"
    const val EXPORT_KML = "KML로 내보내기"

    /** Under the dimmed menu items while the trip is being recorded. */
    const val RECORDING = "기록을 마친 뒤 내보낼 수 있습니다"

    const val SAVED_TITLE = "내보냈습니다"
    const val SHARE = "공유"
    const val OPEN = "파일 열기"
    const val CLOSE = "닫기"

    const val NO_APP = "열 수 있는 앱이 없습니다"
    const val SAVE_FAILED = "저장하지 못했습니다"

    /** The saved dialog's body: where the file is, with the name the phone gave it. */
    fun savedPath(displayName: String): String = "Download/bikeRecoder/$displayName"

    fun menuItem(format: ExportFormat): String = when (format) {
        ExportFormat.GPX -> EXPORT_GPX
        ExportFormat.KML -> EXPORT_KML
    }
}
