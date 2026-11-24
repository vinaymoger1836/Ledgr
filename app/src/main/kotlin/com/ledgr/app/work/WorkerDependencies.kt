package com.ledgr.app.work

import android.content.Context
import com.ledgr.app.data.sms.ScanEngine
import com.ledgr.app.data.sms.ScanProgressRepository
import com.ledgr.app.data.sms.SmsSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * What the workers and the receiver need from the Hilt graph. They are created by WorkManager's default factory
 * (no `hilt-work` dependency, no change to WorkManager start-up), so they look their dependencies up here.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerDependencies {
    fun scanEngine(): ScanEngine
    fun smsSource(): SmsSource
    fun scanProgress(): ScanProgressRepository
}

internal fun Context.workerDependencies(): WorkerDependencies =
    EntryPointAccessors.fromApplication(applicationContext, WorkerDependencies::class.java)
