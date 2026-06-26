package com.EntropicSoftwareLab.ClipboardShare.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.EntropicSoftwareLab.ClipboardShare.data.db.dao.AttemptDao
import com.EntropicSoftwareLab.ClipboardShare.data.db.dao.PeerDao
import com.EntropicSoftwareLab.ClipboardShare.data.db.dao.SessionDao
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.PeerDevice
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.PeerTransportStat
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.SelfGoStat
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransferFileEntity
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransferSessionEntity
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransportAttempt
import com.EntropicSoftwareLab.ClipboardShare.data.db.entity.TransportDim
import com.EntropicSoftwareLab.ClipboardShare.transport.TransportDescriptor

/**
 * V4 §6 schema, version 1. The `transport` dimension is prepopulated from the
 * [TransportDescriptor] enum on create AND verified on open (an APK update
 * that adds a tier must extend the dimension before any FK references it).
 */
@Database(
    entities = [
        TransportDim::class,
        PeerDevice::class,
        PeerTransportStat::class,
        TransferSessionEntity::class,
        TransferFileEntity::class,
        TransportAttempt::class,
        SelfGoStat::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun sessionDao(): SessionDao
    abstract fun attemptDao(): AttemptDao

    companion object {
        private const val DB_NAME = "offgrid.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, DB_NAME
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            seedTransportDim(db)
                        }
                    })
                    // No Migrations are registered, so a future schema bump would
                    // throw IllegalStateException on every existing install. This DB
                    // is only a local cache/mirror, so wipe-and-recreate on version
                    // change is acceptable and safer than crashing.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }

        private fun seedTransportDim(db: SupportSQLiteDatabase) {
            for (t in TransportDescriptor.values()) {
                db.execSQL(
                    "INSERT OR IGNORE INTO transport (transport_id, name) VALUES (?, ?)",
                    arrayOf<Any>(t.id, t.wireName)
                )
            }
        }
    }
}
