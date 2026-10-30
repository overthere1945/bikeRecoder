package com.cowork.bikerecoder.ui.history

/** The dialog over the summary feed or the trip detail. */
sealed interface HistoryDialog {
    /** Why it cannot be deleted. */
    data class Message(val text: String) : HistoryDialog

    /** "Delete?" for a trip or a segment id. */
    data class ConfirmDelete(val id: Long) : HistoryDialog

    /** "짧은 기록 {count}개를 삭제할까요?" for [정리] (spec §6.10). */
    data class ConfirmCleanup(val count: Int) : HistoryDialog
}
