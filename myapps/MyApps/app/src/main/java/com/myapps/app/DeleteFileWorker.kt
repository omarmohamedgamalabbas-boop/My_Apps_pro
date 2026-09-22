package com.myapps.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

class DeleteFileWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val path = inputData.getString("path") ?: return Result.failure()
        val file = File(path)
        if (file.exists()) file.delete()
        return Result.success()
    }
}
