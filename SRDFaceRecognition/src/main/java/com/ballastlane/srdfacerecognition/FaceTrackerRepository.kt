package com.example.pocfacerecognition

import android.util.Log
import com.apollographql.apollo3.exception.ApolloException
import com.example.pocfacerecognition.common.TrackingEvent
import com.example.pocfacerecognition.common.TrackingEventType
import com.example.pocfacerecognition.common.apolloClient
import com.example.pocfacerecognition.common.getTimestamp
import com.example.pocfacerecognition.type.CreateEventInput

class FaceTrackerRepository {
    private var lastEventType: TrackingEventType = TrackingEventType.FACE_FOUND

    @Throws(Exception::class)
    suspend fun sendTrackingEvent(trackingEvent: TrackingEvent) {
        if (trackingEvent.event != TrackingEventType.FACE_FOUND && trackingEvent.event != lastEventType) {
            lastEventType = trackingEvent.event
            println("SRD event is ${trackingEvent.event} y lastEvent ${lastEventType}")

             try {
                 val response = apolloClient.mutation(
                    CreateEventMutation(
                        input = CreateEventInput(
                            type = trackingEvent.event.toEventType(),
                            timestamp = getTimestamp(),
                            metaData = "{}"
                        )
                    )
                ).execute()
                Log.d("CreateEvent", "Success ${response.data}")
            } catch (e: ApolloException) {
                Log.w("sendTrackingEvent", "Failed", e)
                throw e
            }
        }
    }
}