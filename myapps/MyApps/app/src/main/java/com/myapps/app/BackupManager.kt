package com.myapps.app

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object BackupManager {
    fun exportContacts(context: Context): File {
        val array = JSONArray()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                array.put(JSONObject().apply {
                    put("name", c.getString(0) ?: "")
                    put("number", c.getString(1) ?: "")
                    put("type", c.getInt(2))
                })
            }
        }
        return write(context, "contacts", array)
    }

    fun exportSms(context: Context): File {
        val array = JSONArray()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            null, null, Telephony.Sms.DATE + " DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                array.put(JSONObject().apply {
                    put("address", c.getString(0) ?: "")
                    put("body", c.getString(1) ?: "")
                    put("date", c.getLong(2))
                    put("type", c.getInt(3))
                })
            }
        }
        return write(context, "sms", array)
    }

    fun exportCallLog(context: Context): File {
        val array = JSONArray()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
            null, null, CallLog.Calls.DATE + " DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                array.put(JSONObject().apply {
                    put("number", c.getString(0) ?: "")
                    put("date", c.getLong(1))
                    put("duration", c.getLong(2))
                    put("type", c.getInt(3))
                })
            }
        }
        return write(context, "call_log", array)
    }

    private fun write(context: Context, type: String, data: JSONArray): File {
        val dir = File(context.getExternalFilesDir("Backups"), "My Apps").apply { mkdirs() }
        val file = File(dir, "${type}_${System.currentTimeMillis()}.json")
        file.writeText(data.toString(), Charsets.UTF_8)
        return file
    }
}
