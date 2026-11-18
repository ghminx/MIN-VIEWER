package org.minviewer.app

/** 요청 시점의 페이지와 원문 사본. 페이지 번호는 0부터 시작. */
data class AiSource(val page: Int, val text: String, val selected: Boolean = false)
