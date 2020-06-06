package dev.schuberth.stan.parsers

import dev.schuberth.stan.model.BookingItem
import dev.schuberth.stan.model.Statement

import com.itextpdf.text.pdf.PdfName
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.FilteredTextRenderListener
import com.itextpdf.text.pdf.parser.ImageRenderInfo
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import com.itextpdf.text.pdf.parser.RenderFilter
import com.itextpdf.text.pdf.parser.TextRenderInfo
import com.itextpdf.text.pdf.parser.Vector

import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.ParseException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Locale

import kotlin.math.abs

object PostbankPDFParser : Parser {
    private val PDF_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    private val STATEMENT_DATE_PATTERN = Regex(
        "Kontoauszug: (.+) vom (\\d\\d\\.\\d\\d\\.\\d\\d\\d\\d) bis (\\d\\d\\.\\d\\d\\.\\d\\d\\d\\d)"
    )
    private val STATEMENT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private const val STATEMENT_BIC_HEADER_2017 = "BIC (SWIFT):"

    private const val BOOKING_PAGE_HEADER_2014 = "Auszug Seite IBAN BIC (SWIFT)"
    private const val BOOKING_PAGE_HEADER_2017 = "Auszug Jahr Seite von IBAN"
    private const val BOOKING_PAGE_HEADER_BALANCE_OLD = "Alter Kontostand"

    private val BOOKING_TABLE_HEADER = Regex("Buchung[ /]Wert Vorgang/Buchungsinformation Soll Haben")

    private val BOOKING_ITEM_PATTERN = Regex("(\\d\\d\\.\\d\\d\\.)[ /](\\d\\d\\.\\d\\d\\.) (.+) ([+-] ?[\\d.,]+)")
    private val BOOKING_ITEM_PATTERN_NO_SIGN = Regex("(\\d\\d\\.\\d\\d\\.)[ /](\\d\\d\\.\\d\\d\\.) (.+) ([\\d.,]+)")

    private const val BOOKING_SUMMARY_IN = "Kontonummer BLZ Summe Zahlungseingänge"
    private const val BOOKING_SUMMARY_OUT = "Dispositionskredit Zinssatz für Dispositionskredit Summe Zahlungsausgänge"
    private const val BOOKING_SUMMARY_OUT_ALT =
        "Eingeräumte Kontoüberziehung Zinssatz für eingeräumte Kontoüberziehung Summe Zahlungsausgänge"
    private const val BOOKING_SUMMARY_BALANCE_SINGULAR = "Zinssatz für geduldete Überziehung Anlage Neuer Kontostand"
    private const val BOOKING_SUMMARY_BALANCE_PLURAL = "Zinssatz für geduldete Überziehung Anlagen Neuer Kontostand"
    private val BOOKING_SUMMARY_PATTERN = Regex("(.*) ?(EUR) ([+-] [\\d.,]+)")

    private val BOOKING_SYMBOLS = DecimalFormatSymbols(Locale.GERMAN)
    private val BOOKING_FORMAT = DecimalFormat("+ 0,000.#;- 0,000.#", BOOKING_SYMBOLS)

    /*
     * Use an extraction strategy that allow to customize the ratio between the regular character width and the space
     * character width to tweak recognition of word boundaries. Inspired by http://stackoverflow.com/a/13645183/1127485.
     */
    private class MyLocationTextExtractionStrategy(
        private val spaceCharWidthFactor: Float
    ) : LocationTextExtractionStrategy() {
        override fun isChunkAtWordBoundary(chunk: TextChunk, previousChunk: TextChunk): Boolean {
            val width = chunk.location.charSpaceWidth
            if (width < 0.1f) {
                return false
            }

            val dist = chunk.location.distParallelStart() - previousChunk.location.distParallelEnd()
            return dist < -width || dist > width * spaceCharWidthFactor
        }
    }

    private fun parseBookingSummary(startMarker: String, startLine: String, it: ListIterator<String>): Float {
        var marker = startMarker
        var line = startLine

        // Allow the marker to span multiple lines by incrementally
        // removing the current line from the beginning of the marker.
        do {
            marker = marker.replaceFirst(Regex("^$line ?"), "")

            if (it.hasNext()) line = it.next()

            if (marker.isEmpty()) {
                // Full marker match, next line is the one we are interested in.
                break
            }

            // No match yet, take the next line into consideration.
        } while (marker.startsWith(line))

        var m = BOOKING_SUMMARY_PATTERN.matchEntire(line)
        if (m == null) {
            // Try appending the next line before we fail.
            if (it.hasNext()) {
                var twoLines = line.trim() + " " + it.next().trim()
                m = BOOKING_SUMMARY_PATTERN.matchEntire(twoLines)

                // Try prepending the next line before we fail.
                if (m == null) {
                    twoLines = it.previous().trim() + " " + line.trim()
                    m = BOOKING_SUMMARY_PATTERN.matchEntire(twoLines)
                }
            }

            if (m == null) {
                throw ParseException("Error parsing booking summary", it.nextIndex())
            }
        }

        return BOOKING_FORMAT.parse(m.groupValues[3]).toFloat()
    }

    override fun parse(statementFile: File): Statement {
        val filename = statementFile.absolutePath

        val reader = try {
            PdfReader(filename)
        } catch (e: IOException) {
            throw ParseException("Error opening file '$filename'.", 0)
        }

        val pdfInfo = reader.info
        val pdfCreationDate = pdfInfo["CreationDate"]?.let { creationDate ->
            LocalDate.parse(creationDate.substring(2, 16), PDF_DATE_FORMATTER).also {
                if (it.isBefore(LocalDate.of(2014, 7, 1))) {
                    throw ParseException("Unsupported statement format", 0)
                }
            }
        }

        val isFormat2014 = pdfCreationDate?.isBefore(LocalDate.of(2017, 6, 1)) ?: false
        val text = StringBuilder()

        for (i in 1..reader.numberOfPages) {
            val pageResources = reader.getPageResources(i) ?: continue
            val pageFonts = pageResources.getAsDict(PdfName.FONT) ?: continue

            if (isFormat2014) {
                // Ignore the ToUnicode tables of non-embedded fonts to fix garbled text being extracted, see
                // http://stackoverflow.com/a/37786643/1127485.
                pageFonts.keys
                    .map { pageFonts.getAsDict(it) }
                    .forEach { it.put(PdfName.TOUNICODE, null) }
            }

            // Create a filter to ignore vertical text.
            val filter = object : RenderFilter() {
                override fun allowText(renderInfo: TextRenderInfo?): Boolean {
                    val line = renderInfo!!.baseline
                    return line.startPoint.get(Vector.I1) != line.endPoint.get(Vector.I1)
                }

                override fun allowImage(renderInfo: ImageRenderInfo?) = false
            }

            // For some reason we must not share the strategy across pages to get correct results.
            val strategy = FilteredTextRenderListener(MyLocationTextExtractionStrategy(0.3f), filter)
            try {
                text.append(PdfTextExtractor.getTextFromPage(reader, i, strategy))
            } catch (e: IOException) {
                throw ParseException("Error extracting text from page", i)
            }

            // Ensure text from each page ends with a new-line to separate from the first line on the next page.
            text.append("\n")
        }

        val lines = text.lines().dropLastWhile { it.isBlank() }

        var foundStart = false

        var currentItem: BookingItem? = null
        val items = ArrayList<BookingItem>()

        var stFrom: LocalDate? = null
        var stTo: LocalDate? = null
        var postYear = LocalDate.now().year
        var valueYear = LocalDate.now().year
        var accIban: String? = null
        var accBic: String? = null
        var sumIn: Float? = null
        var sumOut: Float? = null
        var balanceOld: Float? = null
        var balanceNew: Float? = null

        var signLine: String? = null

        val bookingPageHeader = if (isFormat2014) BOOKING_PAGE_HEADER_2014 else BOOKING_PAGE_HEADER_2017

        val it = lines.listIterator()
        while (it.hasNext()) {
            var line = it.next()

            var m = STATEMENT_DATE_PATTERN.matchEntire(line)
            if (m != null) {
                if (stFrom != null) {
                    throw ParseException("Multiple statement start dates found", it.nextIndex())
                }
                stFrom = LocalDate.parse(m.groupValues[2], STATEMENT_DATE_FORMATTER)

                if (stTo != null) {
                    throw ParseException("Multiple statement end dates found", it.nextIndex())
                }
                stTo = LocalDate.parse(m.groupValues[3], STATEMENT_DATE_FORMATTER)

                valueYear = stFrom!!.year
                postYear = valueYear
            } else if (!isFormat2014 && line.startsWith(STATEMENT_BIC_HEADER_2017)) {
                accBic = line.removePrefix(STATEMENT_BIC_HEADER_2017).trim()
            } else if (line.startsWith(bookingPageHeader) && it.hasNext()) {
                // Read the IBAN and BIC from the page header.
                val pageIban = StringBuilder(22)

                val info = it.next().split(" ").dropLastWhile { it.isBlank() }

                if (info.size >= 9) {
                    // Only the 2014 format has the BIC in the page header.
                    if (isFormat2014) {
                        if (accBic != null && accBic != info[8]) {
                            throw ParseException("Inconsistent BIC", it.nextIndex())
                        }
                        accBic = info[8]
                    }

                    val ibanOffset = if (isFormat2014) 2 else 4
                    for (i in 0..5) {
                        pageIban.append(info[ibanOffset + i])
                    }

                    if (accIban != null && accIban != pageIban.toString()) {
                        throw ParseException("Inconsistent IBAN", it.nextIndex())
                    }
                    accIban = pageIban.toString()
                }

                val oldBalanceOffset = if (isFormat2014) 10 else 11
                if (line.endsWith(BOOKING_PAGE_HEADER_BALANCE_OLD) && info.size == oldBalanceOffset + 2) {
                    val signStr = info[oldBalanceOffset]
                    var amountStr = info[oldBalanceOffset + 1]

                    // Work around a period being used instead of comma.
                    val amountChars = amountStr.toCharArray()
                    val index = amountStr.length - 3
                    if (amountChars[index] == '.') {
                        amountChars[index] = ','
                        amountStr = String(amountChars)
                    }

                    balanceOld = BOOKING_FORMAT.parse("$signStr $amountStr").toFloat()
                }

                // Start looking for the table header again.
                foundStart = false

                continue
            } else if (BOOKING_SUMMARY_IN.startsWith(line)) {
                sumIn = parseBookingSummary(BOOKING_SUMMARY_IN, line, it)
                continue
            } else if (BOOKING_SUMMARY_OUT.startsWith(line)) {
                sumOut = parseBookingSummary(BOOKING_SUMMARY_OUT, line, it)
                continue
            } else if (BOOKING_SUMMARY_OUT_ALT.startsWith(line)) {
                sumOut = parseBookingSummary(BOOKING_SUMMARY_OUT_ALT, line, it)
                continue
            } else if (BOOKING_SUMMARY_BALANCE_SINGULAR.startsWith(line)) {
                balanceNew = parseBookingSummary(BOOKING_SUMMARY_BALANCE_SINGULAR, line, it)

                // This is the last thing we are interested to parse, so break out of the loop early to avoid the need
                // to filter out coming unwanted stuff.
                break
            } else if (BOOKING_SUMMARY_BALANCE_PLURAL.startsWith(line)) {
                balanceNew = parseBookingSummary(BOOKING_SUMMARY_BALANCE_PLURAL, line, it)

                // This is the last thing we are interested to parse, so break out of the loop early to avoid the need
                // to filter out coming unwanted stuff.
                break
            }
            if (line == "+" || line == "-") {
                signLine = line
                continue
            }

            // Loop until the booking table header is found, and then skip it.
            if (!foundStart) {
                if (line.matches(BOOKING_TABLE_HEADER)) {
                    foundStart = true
                }

                continue
            }

            m = BOOKING_ITEM_PATTERN.matchEntire(line)

            if (m == null) {
                // Work around the sign being present on the previous line.
                m = BOOKING_ITEM_PATTERN_NO_SIGN.matchEntire(line)
                if (m != null && signLine != null) {
                    line = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], signLine, m.groupValues[4])
                        .joinToString(" ")
                    signLine = null
                    m = BOOKING_ITEM_PATTERN.matchEntire(line)
                }
            }

            // Within the booking table, a matching pattern creates a new booking item.
            if (m != null) {
                var postDate = LocalDate.parse(m.groupValues[1] + postYear, STATEMENT_DATE_FORMATTER)
                var valueDate = LocalDate.parse(m.groupValues[2] + valueYear, STATEMENT_DATE_FORMATTER)

                if (currentItem != null) {
                    // If there is a wrap-around in the month, increase the year.
                    if (postDate.month.value < currentItem.postDate.month.value) {
                        postDate = postDate.withYear(++postYear)
                    }

                    if (valueDate.month.value < currentItem.valueDate.month.value) {
                        valueDate = valueDate.withYear(++valueYear)
                    }
                }

                var amountStr = m.groupValues[4]

                // Work around a missing space before the amount.
                if (amountStr[1] != ' ') {
                    amountStr = "${amountStr.take(1)} ${amountStr.drop(1)}"
                }

                val amount = BOOKING_FORMAT.parse(amountStr).toFloat()

                currentItem = BookingItem(postDate, valueDate, m.groupValues[3], amount)
                items.add(currentItem)
            } else {
                // Add the line as info to the current booking item, if any.
                currentItem?.info?.add(line)
            }
        }

        if (stFrom == null) {
            throw ParseException("No statement start date found", it.nextIndex())
        }
        if (stTo == null) {
            throw ParseException("No statement end date found", it.nextIndex())
        }

        if (accIban == null) {
            throw ParseException("No IBAN found", it.nextIndex())
        }
        if (accBic == null) {
            throw ParseException("No BIC found", it.nextIndex())
        }

        if (sumIn == null) {
            throw ParseException("No incoming booking summary found", it.nextIndex())
        }
        if (sumOut == null) {
            throw ParseException("No outgoing booking summary found", it.nextIndex())
        }

        if (balanceOld == null) {
            throw ParseException("No old balance found", it.nextIndex())
        }
        if (balanceNew == null) {
            throw ParseException("No new balance found", it.nextIndex())
        }

        var calcIn = 0.0f
        var calcOut = 0.0f
        for (item in items) {
            if (item.amount > 0) {
                calcIn += item.amount
            } else {
                calcOut += item.amount
            }
        }

        if (abs(calcIn - sumIn) >= 0.01) {
            throw ParseException("Sanity check on incoming booking summary failed", it.nextIndex())
        }

        if (abs(calcOut - sumOut) >= 0.01) {
            throw ParseException("Sanity check on outgoing booking summary failed", it.nextIndex())
        }

        val balanceCalc = balanceOld + sumIn + sumOut
        if (abs(balanceCalc - balanceNew) >= 0.01) {
            throw ParseException("Sanity check on balances failed", it.nextIndex())
        }

        return Statement(
            filename = filename,
            locale = Locale.GERMANY,
            bankId = accBic,
            accountId = accIban,
            fromDate = stFrom,
            toDate = stTo,
            balanceOld = balanceOld,
            balanceNew = balanceNew,
            sumIn = sumIn,
            sumOut = sumOut,
            bookings = items
        )
    }
}
