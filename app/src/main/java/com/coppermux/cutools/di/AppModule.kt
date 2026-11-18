package com.coppermux.cutools.di

import com.coppermux.cutools.data.local.CuToolsDatabase
import com.coppermux.cutools.data.repository.SessionRepository
import com.coppermux.cutools.data.repository.SessionRepositoryImpl
import com.coppermux.cutools.domain.PingService
import com.coppermux.cutools.domain.SessionNameGenerator
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideSessionNameGenerator(): SessionNameGenerator = SessionNameGenerator()

    @Provides
    @Singleton
    fun providePingService(client: OkHttpClient): PingService = PingService(client)

    @Provides
    @Singleton
    fun provideSessionRepository(database: CuToolsDatabase): SessionRepository =
        SessionRepositoryImpl(database.workspaceSessionDao())

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CuToolsDatabase {
        return Room.databaseBuilder(
            context,
            CuToolsDatabase::class.java,
            "cutools_database"
        )
        .addMigrations(CuToolsDatabase.MIGRATION_1_2)
        .build()
    }
}