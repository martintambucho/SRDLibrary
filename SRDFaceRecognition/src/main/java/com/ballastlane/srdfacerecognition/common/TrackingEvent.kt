package com.example.pocfacerecognition.common

data class TrackingEvent(
    var frame: SRDImage? = null,
    var event: TrackingEventType
){
    companion object{
        val default = TrackingEvent(null, TrackingEventType.MOVED_AWAY)
    }
}
