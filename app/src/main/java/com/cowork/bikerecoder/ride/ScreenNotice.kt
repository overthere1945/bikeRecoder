package com.cowork.bikerecoder.ride

/** 음성과 같은 문구를 화면에 한 번 보인다(스낵바). [id]는 알림마다 새 값. */
data class ScreenNotice(val id: Long, val text: String)
