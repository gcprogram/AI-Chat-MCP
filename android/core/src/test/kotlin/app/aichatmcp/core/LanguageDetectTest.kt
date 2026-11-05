package app.aichatmcp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageDetectTest {
    private val samples = mapOf(
        "de-DE" to listOf(
            "Der Hafen von Rotterdam ist der größte Seehafen Europas. Jährlich werden dort mehr als vierhundert Millionen Tonnen Güter umgeschlagen. Besonders wichtig sind Container, Erdöl und Kohle, und der Hafen reicht weit ins Landesinnere hinein.",
            "Es war einmal ein kleines Mädchen, das lebte mit seiner Mutter in einem Haus am Rande des Waldes. Jeden Morgen ging es hinaus, um Beeren zu sammeln, und abends erzählte es der Mutter von den Tieren, die es gesehen hatte.",
        ),
        "en-US" to listOf(
            "The port of Rotterdam is the largest seaport in Europe. Every year more than four hundred million tons of goods are handled there. Containers, crude oil and coal are especially important, and the port reaches far into the country.",
            "Once upon a time there was a little girl who lived with her mother in a house at the edge of the forest. Every morning she went out to pick berries, and in the evening she told her mother about the animals she had seen.",
        ),
        "fr-FR" to listOf(
            "Le port de Rotterdam est le plus grand port maritime d'Europe. Chaque année, plus de quatre cents millions de tonnes de marchandises y sont traitées. Les conteneurs, le pétrole et le charbon sont particulièrement importants, et le port s'étend loin dans les terres.",
            "Il était une fois une petite fille qui vivait avec sa mère dans une maison à l'orée de la forêt. Chaque matin, elle sortait cueillir des baies, et le soir elle racontait à sa mère les animaux qu'elle avait vus.",
        ),
        "es-ES" to listOf(
            "El puerto de Róterdam es el mayor puerto marítimo de Europa. Cada año se manejan allí más de cuatrocientos millones de toneladas de mercancías. Los contenedores, el petróleo y el carbón son especialmente importantes, y el puerto llega muy adentro del país.",
            "Érase una vez una niña pequeña que vivía con su madre en una casa al borde del bosque. Cada mañana salía a recoger bayas, y por la noche le contaba a su madre los animales que había visto durante el día.",
        ),
        "it-IT" to listOf(
            "Il porto di Rotterdam è il più grande porto marittimo d'Europa. Ogni anno vengono movimentati più di quattrocento milioni di tonnellate di merci. I container, il petrolio e il carbone sono particolarmente importanti, e il porto si estende molto all'interno del paese.",
            "C'era una volta una bambina che viveva con sua madre in una casa ai margini del bosco. Ogni mattina usciva a raccogliere le bacche, e la sera raccontava alla madre degli animali che aveva visto durante la giornata.",
        ),
    )

    @Test
    fun recognizesTheLanguagesByLetterFrequency() {
        for ((tag, texts) in samples) {
            for (text in texts) {
                val guess = assertNotNull(LanguageDetect.detect(text), "keine Erkennung für $tag: ${text.take(40)}")
                assertEquals(tag, guess.tag, "falsch erkannt: ${text.take(40)}")
            }
        }
    }

    @Test
    fun recognizesJapaneseByScript() {
        assertEquals("ja-JP", LanguageDetect.detect("これは日本語の文章です。港は大きくて、たくさんの船が来ます。")?.tag)
    }

    @Test
    fun staysUndecidedForShortOrEmptyText() {
        assertNull(LanguageDetect.detect(""))
        assertNull(LanguageDetect.detect("Hallo Welt, wie geht es dir?"))
        assertNull(LanguageDetect.detect("12345 67890 !!! ??? ... ---"))
    }

    @Test
    fun theMarginTellsHowClearItIs() {
        val guess = LanguageDetect.detect(samples.getValue("de-DE")[0])!!
        assertTrue(guess.confidence > 0.01, "Abstand ${guess.confidence}")
    }
}
