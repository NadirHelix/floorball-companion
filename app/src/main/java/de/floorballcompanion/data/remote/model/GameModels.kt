package de.floorballcompanion.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable;

@Serializable
data class GameDetail(
    val id: Int,
    @SerialName("game_number") val gameNumber: String,
    val date: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("actual_start_time") val actualStartTime: String? = null,
    @SerialName("game_status") val gameStatus: String,
    @SerialName("ingame_status") val ingameStatus: String? = null,
    val audience: Int? = null,
    @SerialName("live_stream_link") val liveStreamLink: String? = null,
    @SerialName("vod_link") val vodLink: String? = null,

    @SerialName("home_team_id") val homeTeamId: Int,
    @SerialName("home_team_name") val homeTeamName: String,
    @SerialName("home_team_logo") val homeTeamLogo: String? = null,
    @SerialName("guest_team_id") val guestTeamId: Int,
    @SerialName("guest_team_name") val guestTeamName: String,
    @SerialName("guest_team_logo") val guestTeamLogo: String? = null,

    @SerialName("game_day") val gameDay: GameDayTitle? = null,
    val events: List<GameEvent> = emptyList(),
)

@Serializable
data class GameEvent(
    @SerialName("event_id") val eventId: Int,
    @SerialName("event_type") val eventType: String,      // "goal" | "penalty" | "timeout"
    @SerialName("event_team") val eventTeam: String,       // "home" | "guest"
    val period: Int,
    val time: String,
    val sortkey: String? = null,
    @SerialName("home_goals") val homeGoals: Int? = null,
    @SerialName("guest_goals") val guestGoals: Int? = null,
    val number: Int? = null,                               // Trikotnummer
    val assist: Int? = null,                               // Assist-Trikotnummer
    @SerialName("goal_type") val goalType: String? = null,
    @SerialName("goal_type_string") val goalTypeString: String? = null,
    @SerialName("penalty_type") val penaltyType: String? = null,
    @SerialName("penalty_type_string") val penaltyTypeString: String? = null,
    @SerialName("penalty_reason") val penaltyReason: Int? = null,
    @SerialName("penalty_reason_string") val penaltyReasonString: String? = null,
)

@Serializable
data class GameResult(
    @SerialName("home_goals") val homeGoals: Int = 0,
    @SerialName("guest_goals") val guestGoals: Int = 0,
    val forfait: Boolean = false,
    val overtime: Boolean = false,
    @SerialName("result_string") val resultString: String? = null,
)

@Serializable
data class ScheduledGame(
    @SerialName("game_id") val gameId: Int? = null,
    val id: Int? = null, // manche Endpunkte nutzen "id" statt "game_id"
    @SerialName("game_number") val gameNumber: Int? = null,
    val date: String = "",
    val time: String = "",
    @SerialName("game_day") val gameDayNumber: Int? = null,
    @SerialName("home_team_id") val homeTeamId: Int = 0,
    @SerialName("home_team_name") val homeTeamName: String = "",
    @SerialName("home_team_logo") val homeTeamLogo: String? = null,
    @SerialName("guest_team_id") val guestTeamId: Int = 0,
    @SerialName("guest_team_name") val guestTeamName: String = "",
    @SerialName("guest_team_logo") val guestTeamLogo: String? = null,
    val result: GameResult? = null,
    val state: String? = null,
    @SerialName("result_string") val resultString: String? = null,
) {
    /** Hilfsfunktion, da manche Endpunkte game_id, andere id nutzen */
    val resolvedGameId: Int get() = gameId ?: id ?: 0

    val homeGoals: Int? get() = result?.homeGoals
    val guestGoals: Int? get() = result?.guestGoals
    val startTime: String get() = time
    val gameStatus: String? get() = state
}