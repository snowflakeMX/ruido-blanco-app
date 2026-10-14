package ruido.core

/**
 * Broad kinds of sound, as told by the on-device classifier. Room sounds come from the
 * bedroom itself (snoring, coughing, the kid crying): they never start the masking and
 * never count as neighbor noise.
 */
enum class SoundKind(val neighbors: Boolean) {
    MUSIC(true),
    VOICES(true),
    KNOCKS(true),
    DOORS(true),
    STEPS(true),
    DOG(true),
    TV(true),
    ROOM(false),
    OTHER(true);

    companion object {
        /** Minimum score for a room sound to win; below it the noise is kept as a neighbor's. */
        const val ROOM_MIN_SCORE = 0.25f

        /** Labels that describe the masking noise itself or silence: they say nothing. */
        private val ignored = setOf(
            "Silence", "White noise", "Pink noise", "Static", "Noise", "Hum", "Environmental noise",
            "Mains hum", "Inside, small room", "Inside, large room or hall", "Rain", "Rain on surface",
            "Raindrop", "Waves, surf", "Ocean", "Mechanical fan", "Air conditioning", "Wind", "Rustle",
        )

        private val room = setOf(
            "Snoring", "Cough", "Sneeze", "Breathing", "Wheeze", "Gasp", "Pant", "Snort", "Sniff",
            "Throat clearing", "Sigh", "Baby cry, infant cry", "Crying, sobbing", "Baby laughter", "Whimper",
            "Burping, eructation", "Hiccup",
        )

        /** Kind of one classifier label, or null if the label is ignored. */
        fun ofLabel(label: String): SoundKind? {
            if (label in ignored) return null
            if (label in room) return ROOM
            val l = label.lowercase()
            return when {
                "music" in l || "guitar" in l || "drum" in l || "bass" in l || "singing" in l || "song" in l -> MUSIC
                label == "Television" || label == "Radio" -> TV
                "door" in l || label == "Slam" -> DOORS
                "footstep" in l || label == "Walk, footsteps" || label == "Run" -> STEPS
                label == "Dog" || "bark" in l || "dogs" in l || label == "Howl" || label == "Whimper (dog)" -> DOG
                "speech" in l || label == "Shout" || label == "Yell" || "laugh" in l || label == "Conversation" ||
                    "babble" in l || label == "Whispering" || label == "Children shouting" -> VOICES
                label == "Knock" || "thump" in l || label == "Bang" || label == "Tap" || label == "Clatter" ||
                    "hammer" in l || label == "Wood" || "furniture" in l -> KNOCKS
                else -> OTHER
            }
        }

        /**
         * Decides the kind from the classifier's [scores] for each of its [labels]. A room
         * sound wins only when it is the strongest meaningful label and clearly present.
         */
        fun classify(labels: List<String>, scores: FloatArray): SoundKind {
            var best: SoundKind? = null
            var bestScore = 0f
            var roomScore = 0f
            for (i in labels.indices) {
                val kind = ofLabel(labels[i]) ?: continue
                val score = scores.getOrElse(i) { 0f }
                if (kind == ROOM) roomScore = maxOf(roomScore, score)
                if (score > bestScore) {
                    best = kind
                    bestScore = score
                }
            }
            if (best == ROOM && roomScore < ROOM_MIN_SCORE) return OTHER
            return best ?: OTHER
        }
    }
}
