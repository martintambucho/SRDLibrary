package com.example.pocfacerecognition.common

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences


object SRDPreferences {
    private var mSharedPref: SharedPreferences? = null
    const val API_KEY = "API_KEY"
    fun init(context: Context) {
        if (mSharedPref == null) mSharedPref =
            context.getSharedPreferences(context.packageName, Activity.MODE_PRIVATE)
    }

    fun read(key: String?, defValue: String?): String {
        return mSharedPref!!.getString(key, defValue) ?: ""
    }

    fun write(key: String?, value: String?) {
        val prefsEditor = mSharedPref!!.edit()
        prefsEditor.putString(key, value)
        prefsEditor.apply()
    }
}