package com.example.pocfacerecognition.common

import com.example.pocfacerecognition.type.EventType

enum class TrackingEventType {
    FACE_FOUND,
    MOVED_AWAY,
    MULTIPLE_SUBJECTS,
    UNKNOWN_SUBJECT;

    fun toEventType(): EventType {
        return when (this) {
            MOVED_AWAY -> EventType.MOVED_AWAY
            MULTIPLE_SUBJECTS -> EventType.MULTIPLE_SUBJECTS
            UNKNOWN_SUBJECT -> EventType.UNKNOWN_SUBJECT
            FACE_FOUND -> EventType.MOVED_AWAY
        }
    }
}
