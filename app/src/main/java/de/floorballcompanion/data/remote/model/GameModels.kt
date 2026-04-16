package de.floorballcompanion.data.remote.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Deserialisiert Int-Felder die auch als leerer String "" kommen koennen → 0 */
internal object LenientIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return 0
        return element.intOrNull ?: element.content.toIntOrNull() ?: 0
    }
}

@Serializable
data class GameDetail(
    val id: Int,
    @SerialName("game_number") val gameNumber: String,
    val date: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("actual_start_time") val actualStartTime: String? = null,
    @SerialName("game_status") val gameStatus: String? = "UNKNOWN",
    @SerialName("ingame_status") val ingameStatus: String? = null,
    val audience: Int? = null,
    @SerialName("live_stream_link") val liveStreamLink: String? = null,
    @SerialName("vod_link") val vodLink: String? = null,

    @SerialName("home_team_id") val homeTeamId: Int = 0,
    @SerialName("home_team_name") val homeTeamName: String? = null,
    @SerialName("home_team_logo") val homeTeamLogo: String? = null,
    @SerialName("guest_team_id") val guestTeamId: Int = 0,
    @SerialName("guest_team_name") val guestTeamName: String? = null,
    @SerialName("guest_team_logo") val guestTeamLogo: String? = null,

    @SerialName("game_day") val gameDay: GameDayTitle? = null,
    val events: List<GameEvent> = emptyList(),
    val result: GameResult? = null,
    @SerialName("result_string") val resultString: String? = null,
    val started: Boolean = false,
    val ended: Boolean = false,

    // Kader
    val players: GamePlayers? = null,
    @SerialName("starting_players") val startingPlayers: GameStartingPlayers? = null,
    val awards: GameAwards? = null,

    // Spielinfos
    @SerialName("league_id") val leagueId: Int? = null,
    @SerialName("league_name") val leagueName: String? = null,
    @SerialName("arena_name") val arenaName: String? = null,
    @SerialName("arena_address") val arenaAddress: String? = null,
    @SerialName("arena_short") val arenaShort: String? = null,
    @SerialName("nominated_referees") val nominatedReferees: String? = null,
    val referees: List<GameReferee> = emptyList(),
)

@Serializable
data class GamePlayers(
    val home: List<GamePlayer> = emptyList(),
    val guest: List<GamePlayer> = emptyList(),
)

@Serializable
data class GamePlayer(
    @Serializable(with = LenientIntSerializer::class)
    @SerialName("player_id") val playerId: Int = 0,
    @SerialName("player_name") val playerName: String = "",
    @SerialName("player_firstname") val playerFirstname: String = "",
    @Serializable(with = LenientIntSerializer::class)
    @SerialName("trikot_number") val trikotNumber: Int = 0,
    val position: String = "",
    val goalkeeper: Boolean = false,
    val captain: Boolean = false,
)

@Serializable
data class GameStartingPlayers(
    val home: List<StartingPlayer> = emptyList(),
    val guest: List<StartingPlayer> = emptyList(),
)

@Serializable
data class StartingPlayer(
    val position: String = "",
    @Serializable(with = LenientIntSerializer::class)
    @SerialName("player_id") val playerId: Int = 0,
    @SerialName("player_firstname") val playerFirstname: String = "",
    @SerialName("player_name") val playerName: String = "",
    @Serializable(with = LenientIntSerializer::class)
    @SerialName("trikot_number") val trikotNumber: Int = 0,
    val team: String = "",
)

@Serializable
data class GameAwards(
    val home: List<GameAward> = emptyList(),
    val guest: List<GameAward> = emptyList(),
)

@Serializable
data class GameAward(
    val award: String = "",
    @Serializable(with = LenientIntSerializer::class)
    @SerialName("player_id") val playerId: Int = 0,
    @SerialName("player_firstname") val playerFirstname: String = "",
    @SerialName("player_name") val playerName: String = "",
    @Serializable(with = LenientIntSerializer::class)
    @SerialName("trikot_number") val trikotNumber: Int = 0,
    val team: String = "",
)

@Serializable
data class GameReferee(
    @SerialName("license_id") val licenseId: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
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
data class ResultPostfix(
    val short: String = "",
    val long: String = "",
)

@Serializable
data class GameResult(
    @SerialName("home_goals") val homeGoals: Int = 0,
    @SerialName("guest_goals") val guestGoals: Int = 0,
    val forfait: Boolean = false,
    val overtime: Boolean = false,
    @SerialName("result_string") val resultString: String? = null,
    @SerialName("home_goals_period") val homeGoalsPeriod: List<Int> = emptyList(),
    @SerialName("guest_goals_period") val guestGoalsPeriod: List<Int> = emptyList(),
    val postfix: ResultPostfix? = null,
) {
    /** Kürzel wie "n.V." oder "n. PS", leer wenn regulär beendet */
    val postfixShort: String get() = postfix?.short ?: ""
}

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
    // Playoff/Cup-spezifische Felder
    @SerialName("series_title") val seriesTitle: String? = null,
    @SerialName("series_number") val seriesNumber: String? = null,
    @SerialName("home_team_filling_title") val homeTeamFillingTitle: String? = null,
    @SerialName("guest_team_filling_title") val guestTeamFillingTitle: String? = null,
) {
    /** Hilfsfunktion, da manche Endpunkte game_id, andere id nutzen */
    val resolvedGameId: Int get() = gameId ?: id ?: 0

    val homeGoals: Int? get() = result?.homeGoals
    val guestGoals: Int? get() = result?.guestGoals
    val startTime: String get() = time
    val gameStatus: String? get() = state
}