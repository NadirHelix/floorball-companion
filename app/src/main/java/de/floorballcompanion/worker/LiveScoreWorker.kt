package de.floorballcompanion.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.floorballcompanion.FloorballApp
import de.floorballcompanion.MainActivity
import de.floorballcompanion.R
import de.floorballcompanion.data.remote.model.GameDetail
import de.floorballcompanion.data.remote.model.GameEvent
import de.floorballcompanion.data.remote.model.ScheduledGame
import de.floorballcompanion.data.repository.FloorballRepository
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

@HiltWorker
class LiveScoreWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: FloorballRepository,
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "live_score_poll"

        /** Startet periodisches Polling alle 15 Minuten */
        fun enqueuePeriodicWork(context: Context) {
            val request = PeriodicWorkRequestBuilder<LiveScoreWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Startet aggressiveres Polling (einmalig, wiederholt sich
         * selbst solange Live-Spiele laufen).
         */
        fun enqueueOneTimeWork(context: Context) {
            val request = OneTimeWorkRequestBuilder<LiveScoreWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(60, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_oneshot",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun doWork(): Result {
        return try {
            val favoriteLeagueIds = repository.getFavoriteLeagueIds()
            val favoriteTeamIds = repository.getFavoriteTeamIds()

            if (favoriteLeagueIds.isEmpty() && favoriteTeamIds.isEmpty()) {
                return Result.success()
            }

            var hasLiveGames = false

            for (leagueId in favoriteLeagueIds) {
                val schedule = try {
                    repository.getCurrentGameDay(leagueId)
                } catch (e: Exception) { continue }

                for (game in schedule) {
                    val isRelevant = game.homeTeamId in favoriteTeamIds ||
                            game.guestTeamId in favoriteTeamIds ||
                            favoriteTeamIds.isEmpty() // Alle Spiele der Liga

                    if (!isRelevant) continue

                    if (game.gameStatus == "live") {
                        hasLiveGames = true
                        checkForGoalUpdates(game)
                    }
                }
            }

            // Bei laufenden Spielen: in 60s erneut pollen
            if (hasLiveGames) {
                enqueueOneTimeWork(applicationContext)
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private suspend fun checkForGoalUpdates(game: ScheduledGame) {
        try {
            val detail = repository.getGameDetail(game.resolvedGameId)
            val lastGoal = detail.events
                .filter { it.eventType == "goal" }
                .maxByOrNull { it.eventId }
                ?: return

            // Prüfe ob dieses Tor schon gemeldet wurde (via SharedPrefs)
            val prefs = applicationContext.getSharedPreferences("live_scores", Context.MODE_PRIVATE)
            val lastNotifiedEventKey = "game_${game.resolvedGameId}_last_event"
            val lastNotifiedEvent = prefs.getInt(lastNotifiedEventKey, 0)

            if (lastGoal.eventId > lastNotifiedEvent) {
                sendGoalNotification(detail, lastGoal)
                prefs.edit { putInt(lastNotifiedEventKey, lastGoal.eventId) }
            }
        } catch (_: Exception) { }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun sendGoalNotification(game: GameDetail, goal: GameEvent) {
        val score = "${goal.homeGoals ?: "?"}:${goal.guestGoals ?: "?"}"
        val title = "\uD83E\uDD45 Tor! ${game.homeTeamName} $score ${game.guestTeamName}"
        val body = "${goal.time} — ${if (goal.eventTeam == "home") game.homeTeamName else game.guestTeamName}"

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("game_id", game.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, game.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, FloorballApp.CHANNEL_LIVE)
            .setSmallIcon(R.drawable.placeholder_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(game.id, notification)
        } catch (_: SecurityException) {
            // Notification-Permission nicht erteilt
        }
    }
}