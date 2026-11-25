/*
 * Copyright (c) 2026 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.lninvoice

import app.cash.lninvoice.Bech32Data.Companion.Encoding.BECH32
import app.cash.lninvoice.Invoices.sampleDecoded
import arrow.core.Either
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.arrow.core.shouldBeSome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.crypto.ECKey
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

class PaymentRequestMalformedInputTest : StringSpec({
  "invoice HRPs must match completely and use Bech32" {
    listOf("xlnbc25m", "lnbc25mextra", "lnbc-1", "lnbc1.5m").forEach {
      PaymentRequest.parse(invoice(hrp = it)).shouldBeLeft()
    }
    val encoded = Bech32Data(Bech32Data.Companion.Encoding.BECH32M, "lnbc25m", sampleDecoded.payload).encoded
    PaymentRequest.parse(encoded).shouldBeLeft()
  }

  "unrepresentable amounts return InvalidInvoice for every multiplier" {
    listOf("", "m", "u", "n", "p").forEach { multiplier ->
      val encoded = invoice(hrp = "lnbc${"9".repeat(60)}0$multiplier")
      PaymentRequest.parse(encoded).shouldBeLeft()
      shouldThrow<InvalidInvoice> { PaymentRequest.parseUnsafe(encoded) }
    }
  }

  "amount scaling keeps values beyond the Long pico and nano limits exact" {
    checkAll(100, Arb.long(0L..Long.MAX_VALUE)) { sats ->
      listOf("n" to BigInteger.TEN, "p" to BigInteger.valueOf(10_000)).forEach { (multiplier, factor) ->
        val digits = BigInteger.valueOf(sats).multiply(factor)
        PaymentRequest.parse(invoice(hrp = "lnbc$digits$multiplier"))
          .shouldBeRight().amount shouldBeSome BitcoinAmount(sats)
      }
    }
  }

  "whole milli and micro amounts reject the first scaled satoshi overflow" {
    listOf("" to 100_000_000L, "m" to 100_000L, "u" to 100L).forEach { (multiplier, factor) ->
      val limit = Long.MAX_VALUE / factor
      PaymentRequest.parse(invoice(hrp = "lnbc$limit$multiplier"))
        .shouldBeRight().amount shouldBeSome BitcoinAmount(limit * factor)
      PaymentRequest.parse(invoice(hrp = "lnbc${limit + 1}$multiplier")).shouldBeLeft()
    }
  }

  "large pico amounts still require millisatoshi precision" {
    val pico = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(10_000))
    PaymentRequest.parse(invoice(hrp = "lnbc${pico + BigInteger.TEN}p"))
      .shouldBeRight().amount shouldBeSome BitcoinAmount(Long.MAX_VALUE, 10)
    PaymentRequest.parse(invoice(hrp = "lnbc${pico + BigInteger.ONE}p")).shouldBeLeft()
  }

  "a payment hash must have exactly 52 words" {
    listOf(0, 1, 51, 53, 1023).forEach { size ->
      val fields = sampleFields.filterNot { it.tag == FieldTags.PAYMENT_HASH.tag } + field(FieldTags.PAYMENT_HASH, size)
      PaymentRequest.parse(invoice(fields)).shouldBeLeft()
    }
  }

  "malformed fixed fields reject invoices in either order" {
    val malformedFields = listOf(
      FieldTags.PAYMENT_HASH to listOf(0, 1, 51, 53),
      FieldTags.DESCRIPTION_HASH to listOf(0, 1, 51, 53),
      FieldTags.SECRET to listOf(0, 1, 51, 53),
      FieldTags.PAYEE_NODE to listOf(0, 1, 52, 54),
    ).flatMap { (tag, sizes) -> sizes.map { field(tag, it) } }

    malformedFields.forEach { malformed ->
      val validFields = if (malformed.tag == FieldTags.DESCRIPTION_HASH.tag) {
        sampleFields.filterNot { it.tag == FieldTags.DESCRIPTION.tag } + field(FieldTags.DESCRIPTION_HASH, 52)
      } else {
        sampleFields
      }
      val expectedSize = if (malformed.tag == FieldTags.PAYEE_NODE.tag) 53 else 52
      val expectedError = "Invalid tagged field length. [tag=${malformed.tag}][size=${malformed.size}][expected=$expectedSize]"

      PaymentRequest.parse(invoice(validFields)).shouldBeRight()
      PaymentRequest.parse(invoice(listOf(malformed) + validFields)).shouldBeLeft().message shouldBe expectedError
      PaymentRequest.parse(invoice(validFields + malformed)).shouldBeLeft().message shouldBe expectedError
    }
  }

  "payment hashes and secrets must each occur exactly once in either parsing mode" {
    val invalidFields = listOf(FieldTags.PAYMENT_HASH, FieldTags.SECRET).flatMap { tag ->
      val original = sampleFields.first { it.tag == tag.tag }
      val without = sampleFields.filterNot { it.tag == tag.tag }
      val duplicate = listOf(original, original)
      val conflicting = listOf(original, field(tag, 52))
      listOf(without, duplicate + without, without + duplicate, conflicting + without, without + conflicting)
    }

    invalidFields.forEach { fields ->
      invoice(fields).shouldFailInBothParsingModes()
    }
  }

  "exactly one description or description hash is required" {
    val without = sampleFields.filterNot {
      it.tag == FieldTags.DESCRIPTION.tag || it.tag == FieldTags.DESCRIPTION_HASH.tag
    }
    val description = sampleFields.first { it.tag == FieldTags.DESCRIPTION.tag }
    val descriptionHash = field(FieldTags.DESCRIPTION_HASH, 52)
    val validDescriptions = listOf(listOf(description), listOf(descriptionHash))
    val invalidDescriptions = listOf(
      emptyList(),
      listOf(description, descriptionHash),
      listOf(descriptionHash, description),
      listOf(description, description),
      listOf(descriptionHash, descriptionHash),
    )

    validDescriptions.forEach { fields ->
      val encoded = invoice(without + fields)
      PaymentRequest.parse(encoded).shouldBeRight()
      PaymentRequest.parse(encoded, strict = true).shouldBeRight()
    }
    invalidDescriptions.forEach { fields ->
      invoice(without + fields).shouldFailInBothParsingModes()
    }
  }

  "descriptions reject BOLT1 prohibited Unicode code points" {
    val prohibitedCodePoints = listOf(
      0x0000, 0x0009, 0x000A, 0x000D, 0x001F, 0x007F, 0x009F,
      0x00AD, 0x061C, 0x200D, 0xFEFF, 0xE000, 0xF8FF, 0xF0000, 0x10FFFD, 0x2028, 0x2029,
    )
    val prohibitedDescriptions = prohibitedCodePoints.flatMap { codePoint ->
      val character = String(Character.toChars(codePoint))
      listOf(character, "a${character}b")
    }

    prohibitedDescriptions.forEach { text ->
      val fields = sampleFields.filterNot { it.tag == FieldTags.DESCRIPTION.tag } + descriptionField(text)
      invoice(fields).shouldFailInBothParsingModes()
    }
  }

  "descriptions preserve allowed Unicode including supplementary and unassigned code points" {
    listOf("", "coffee beans", "ナンセンス 1杯", "café e\u0301", "⚡️ 😀", String(Character.toChars(0x10400)), "\u0378").forEach { text ->
      val fields = sampleFields.filterNot { it.tag == FieldTags.DESCRIPTION.tag } + descriptionField(text)
      PaymentRequest.parse(invoice(fields)).shouldBeRight().description shouldBeSome text
    }
  }

  "invalid UTF-8 descriptions fail during safe parsing" {
    val badUtf8 = TaggedField(FieldTags.DESCRIPTION.tag, 2, byteArrayOf(31, 28).toByteString())
    PaymentRequest.parse(invoice(sampleFields.filterNot { it.tag == FieldTags.DESCRIPTION.tag } + badUtf8)).shouldBeLeft()
  }

  "expiry accepts Long MAX and leading zero words without wrapping" {
    val words = unsignedWords(BigInteger.valueOf(Long.MAX_VALUE))
    listOf(words, (byteArrayOf(0, 0) + words.toByteArray()).toByteString()).forEach {
      val expiry = TaggedField(FieldTags.EXPIRY.tag, it.size, it)
      PaymentRequest.parse(invoice(sampleFields + expiry)).shouldBeRight().expiry shouldBe Duration.ofSeconds(Long.MAX_VALUE)
    }
    PaymentRequest.parse(invoice(sampleFields + field(FieldTags.EXPIRY, 0))).shouldBeRight().expiry shouldBe Duration.ZERO
  }

  "expiry overflow fails during parsing rather than wrapping to a short or negative duration" {
    listOf(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE, BigInteger.ONE.shiftLeft(65)).forEach {
      val words = unsignedWords(it)
      val expiry = TaggedField(FieldTags.EXPIRY.tag, words.size, words)
      PaymentRequest.parse(invoice(sampleFields + expiry)).shouldBeLeft()
    }
  }

  "invalid recovery IDs and out of range signature scalars fail during safe parsing" {
    listOf(4, 27, 255).forEach { id ->
      val signature = sampleSignature.toByteArray().also { it[64] = id.toByte() }.toByteString()
      PaymentRequest.parse(invoice(signature = signature)).shouldBeLeft()
    }
    val order = ECKey.ecDomainParameters().n
    listOf(BigInteger.ZERO, order, BigInteger.ONE.shiftLeft(256) - BigInteger.ONE).forEach { scalar ->
      PaymentRequest.parse(invoice(signature = compactSignature(scalar, BigInteger.ONE, 0))).shouldBeLeft()
      PaymentRequest.parse(invoice(signature = compactSignature(BigInteger.ONE, scalar, 0))).shouldBeLeft()
    }
  }

  "invalid signatures fail when the payee key of a constructed or copied request is read" {
    val request = PaymentRequest.parse(Invoices.sample).shouldBeRight()
    val signature = compactSignature(BigInteger.ZERO, BigInteger.ONE, 0)
    val constructed = PaymentRequest(
      network = request.network,
      timestamp = request.timestamp,
      amount = request.amount,
      paymentHash = request.paymentHash,
      taggedFields = request.taggedFields,
      signature = signature,
      hash = request.hash,
    )
    listOf(constructed, request.copy(signature = signature), request.copy(signature = ByteString.EMPTY)).forEach {
      shouldThrow<InvalidInvoice> { it.payeeNodePublicKey }
    }
  }

  "truncated description hashes fail when copying" {
    val request = PaymentRequest.parse(Invoices.sample).shouldBeRight()
    shouldThrow<IndexOutOfBoundsException> {
      request.copy(taggedFields = listOf(field(FieldTags.DESCRIPTION_HASH, 51)))
    }
  }

  "expiry overflow fails when copying" {
    val request = PaymentRequest.parse(Invoices.sample).shouldBeRight()
    val words = unsignedWords(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE)
    val expiry = TaggedField(FieldTags.EXPIRY.tag, words.size, words)
    shouldThrow<ArithmeticException> { request.copy(taggedFields = listOf(expiry)) }
  }

  "truncated routing data fails when copying" {
    val request = PaymentRequest.parse(Invoices.sample).shouldBeRight()
    val route = TaggedField(FieldTags.EXTRA_ROUTING_INFO.tag, 82, ByteString.EMPTY)
    shouldThrow<IndexOutOfBoundsException> { request.copy(taggedFields = listOf(route)) }
  }

  "unrecoverable curve points fail during parsing" {
    val signature = compactSignature(ECKey.ecDomainParameters().n - BigInteger.ONE, BigInteger.ONE, 2)
    PaymentRequest.parse(invoice(signature = signature)).shouldBeLeft()
  }

  "explicit payee keys must verify the signature" {
    val signer = ECKey()
    val payee = signer.pubKey.toByteString().toWords()
    val fields = sampleFields + TaggedField(FieldTags.PAYEE_NODE.tag, payee.size, payee)
    val digest = invoiceDigest(fields)
    val signature = signer.sign(digest)
    val recoveryId = signer.findRecoveryId(digest, signature).toInt()
    val compact = compactSignature(signature.r, signature.s, recoveryId)
    PaymentRequest.parse(invoice(fields, signature = compact)).shouldBeRight()
      .payeeNodePublicKey shouldBe signer.pubKey.toByteString()

    val wrongSigner = ECKey().sign(digest)
    PaymentRequest.parse(invoice(fields, signature = compactSignature(wrongSigner.r, wrongSigner.s, 0))).shouldBeLeft()
    PaymentRequest.parse(invoice(sampleFields + field(FieldTags.PAYEE_NODE, 53))).shouldBeLeft()

    val malformed = field(FieldTags.PAYEE_NODE, 52)
    listOf(listOf(malformed) + fields, fields + malformed).forEach { orderedFields ->
      val orderedDigest = invoiceDigest(orderedFields)
      val orderedSignature = signer.sign(orderedDigest)
      PaymentRequest.parse(invoice(orderedFields, signature = compactSignature(orderedSignature.r, orderedSignature.s, 0)))
        .shouldBeLeft()
    }
  }

  "recovery accepts high S signatures while explicit payees require low S" {
    val signer = ECKey()
    val digest = invoiceDigest(sampleFields)
    val low = signer.sign(digest)
    val high = ECKey.ECDSASignature(low.r, ECKey.ecDomainParameters().n - low.s)
    val recoveryId = signer.findRecoveryId(digest, high).toInt()
    val request = PaymentRequest.parse(invoice(signature = compactSignature(high.r, high.s, recoveryId))).shouldBeRight()
    request.payeeNodePublicKey shouldBe signer.pubKey.toByteString()

    val payee = signer.pubKey.toByteString().toWords()
    val fields = sampleFields + TaggedField(FieldTags.PAYEE_NODE.tag, payee.size, payee)
    val explicitLow = signer.sign(invoiceDigest(fields))
    val explicitHigh = ECKey.ecDomainParameters().n - explicitLow.s
    PaymentRequest.parse(invoice(fields, signature = compactSignature(explicitLow.r, explicitHigh, 0))).shouldBeLeft()
  }

  "truncated timestamp signature and tagged fields return InvalidInvoice" {
    (0..110).forEach { size ->
      val payload = sampleDecoded.payload.substring(0, size)
      PaymentRequest.parse(Bech32Data(BECH32, "lnbc25m", payload).encoded).shouldBeLeft()
    }
    val truncated = sampleDecoded.payload.substring(0, 7).toByteArray() + byteArrayOf(1, 1, 20, 0) + sampleSignature.toWords().toByteArray()
    PaymentRequest.parse(Bech32Data(BECH32, "lnbc25m", truncated.toByteString()).encoded).shouldBeLeft()
  }

  "the full 35 bit timestamp is supported" {
    val payload = sampleDecoded.payload.toByteArray().also { words -> (0 until 7).forEach { words[it] = 31 } }.toByteString()
    PaymentRequest.parse(Bech32Data(BECH32, "lnbc25m", payload).encoded).shouldBeRight()
      .timestamp shouldBe Instant.ofEpochSecond((1L shl 35) - 1)
  }

  "lenient parsing returns the same request as parse for valid invoices" {
    listOf(Invoices.sample, Invoices.sampleWithDescriptionHash, Invoices.sampleWithRoutingInfo).forEach { encoded ->
      val strict = PaymentRequest.parse(encoded).shouldBeRight()
      val lenient = PaymentRequest.parseLenient(encoded).shouldBeRight()
      lenient shouldBe strict
      lenient.description shouldBe strict.description
      lenient.payeeNodePublicKey shouldBe strict.payeeNodePublicKey
      lenient.routingInfo shouldBe strict.routingInfo
    }
  }

  "lenient parsing accepts invoices that 0.0.6 accepted and 0.0.7 rejects" {
    val withoutSecret = sampleFields.filterNot { it.tag == FieldTags.SECRET.tag }
    val withoutDescription = sampleFields.filterNot { it.tag == FieldTags.DESCRIPTION.tag }
    val shortSecret = withoutSecret + field(FieldTags.SECRET, 51)
    val invalidUtf8 = withoutDescription + TaggedField(FieldTags.DESCRIPTION.tag, 2, byteArrayOf(31, 28).toByteString())
    val encodings = listOf(
      invoice(withoutSecret),
      invoice(withoutDescription),
      invoice(sampleFields + sampleFields.first { it.tag == FieldTags.SECRET.tag }),
      invoice(shortSecret),
      invoice(withoutDescription + descriptionField("a\u200Db")),
      invoice(hrp = "xlnbc25m"),
      invoice() + "\n",
      " " + invoice().uppercase(),
      Bech32Data(Bech32Data.Companion.Encoding.BECH32M, "lnbc25m", sampleDecoded.payload).encoded,
    )

    encodings.forEach { encoded ->
      PaymentRequest.parse(encoded).shouldBeLeft()
      PaymentRequest.parseLenient(encoded).shouldBeRight().should {
        it.network shouldBe Network.MAIN
        it.amount shouldBeSome BitcoinAmount(2_500_000)
        it.paymentHash shouldBe "0001020304050607080900010203040506070809000102030405060708090102"
      }
    }
    PaymentRequest.parseLenient(invoice(withoutDescription + descriptionField("a\u200Db"))).shouldBeRight()
      .description shouldBeSome "a\u200Db"
    PaymentRequest.parse(invoice(invalidUtf8)).shouldBeLeft()
    PaymentRequest.parseLenient(invoice(invalidUtf8)).shouldBeRight().description shouldBeSome "\uFFFD"
  }

  "lenient parsing defers invalid signatures to the payee key" {
    val invalidSignatures = listOf(
      compactSignature(ECKey.ecDomainParameters().n - BigInteger.ONE, BigInteger.ONE, 2),
      sampleSignature.toByteArray().also { it[64] = 4 }.toByteString(),
      compactSignature(BigInteger.ZERO, BigInteger.ONE, 0),
    )
    invalidSignatures.forEach { signature ->
      val encoded = invoice(signature = signature)
      PaymentRequest.parse(encoded).shouldBeLeft()
      val request = PaymentRequest.parseLenient(encoded).shouldBeRight()
      request.description shouldBeSome "coffee beans"
      shouldThrow<InvalidInvoice> { request.payeeNodePublicKey }
    }
  }

  "lenient parsing still rejects invoices whose decoded values would be wrong" {
    val unknownRequiredFeature = sampleFields.filterNot { it.tag == FieldTags.FEATURE_BITS.tag } +
      TaggedField(FieldTags.FEATURE_BITS.tag, 1, byteArrayOf(4).toByteString())
    val expiryWords = unsignedWords(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE)
    val checksumMismatch = invoice().let { it.dropLast(1) + if (it.last() == 'q') 'p' else 'q' }
    listOf(
      checksumMismatch,
      invoice(sampleFields.filterNot { it.tag == FieldTags.PAYMENT_HASH.tag }),
      invoice(hrp = "lnbc${"9".repeat(60)}0"),
      invoice(hrp = "lnbc1p"),
      invoice(unknownRequiredFeature),
      invoice(sampleFields + TaggedField(FieldTags.EXPIRY.tag, expiryWords.size, expiryWords)),
      invoice(sampleFields.filterNot { it.tag == FieldTags.DESCRIPTION.tag } + field(FieldTags.DESCRIPTION_HASH, 51)),
    ).forEach { PaymentRequest.parseLenient(it).shouldBeLeft() }
  }

  "mutated checksummed invoices never throw from parse or successful getters" {
    checkAll(300, Arb.int(0 until sampleDecoded.payload.size), Arb.int(0..31)) { index, word ->
      val payload = sampleDecoded.payload.toByteArray().also { it[index] = word.toByte() }.toByteString()
      val result = PaymentRequest.parse(Bech32Data(BECH32, "lnbc25m", payload).encoded)
      if (result is Either.Right) {
        result.value.apply {
          description
          descriptionHash
          expiry
          payeeNodePublicKey
          routingInfo
        }
      }
    }
  }
})

private val sampleFields: List<TaggedField> =
  BitReader(sampleDecoded.payload.substring(7, sampleDecoded.payload.size - 104)).taggedFields()

private val sampleSignature: ByteString =
  BitReader(sampleDecoded.payload.substring(sampleDecoded.payload.size - 104)).byteString(520)

private fun field(tag: FieldTags, size: Int) = TaggedField(tag.tag, size, ByteArray(size).toByteString())

private fun String.shouldFailInBothParsingModes() {
  PaymentRequest.parse(this).shouldBeLeft()
  PaymentRequest.parse(this, strict = true).shouldBeLeft()
}

private fun descriptionField(text: String): TaggedField {
  val words = text.encodeUtf8().toWords()
  return TaggedField(FieldTags.DESCRIPTION.tag, words.size, words)
}

private fun dataWords(fields: List<TaggedField>): ByteString = (
  sampleDecoded.payload.substring(0, 7).toByteArray() + fields.flatMap {
    listOf(it.tag.toByte(), (it.size shr 5).toByte(), (it.size and 31).toByte()) + it.data.toByteArray().toList()
  }.toByteArray()
).toByteString()

private fun invoice(
  fields: List<TaggedField> = sampleFields,
  hrp: String = "lnbc25m",
  signature: ByteString = sampleSignature,
): String = Bech32Data(BECH32, hrp, (dataWords(fields).toByteArray() + signature.toWords().toByteArray()).toByteString()).encoded

private fun invoiceDigest(fields: List<TaggedField>): Sha256Hash {
  val words = dataWords(fields)
  val bytes = "lnbc25m".encodeUtf8().toByteArray() + BitReader(words).byteString(words.size * 5).toByteArray()
  return Sha256Hash.wrap(bytes.toByteString().sha256().toByteArray())
}

private fun ByteString.toWords(): ByteString = toByteArray().joinToString("") {
  it.toUByte().toString(2).padStart(8, '0')
}.chunked(5).map { it.padEnd(5, '0').toInt(2).toByte() }.toByteArray().toByteString()

private fun unsignedWords(value: BigInteger): ByteString = value.toString(2).let {
  it.padStart((it.length + 4) / 5 * 5, '0').chunked(5).map { word -> word.toInt(2).toByte() }.toByteArray().toByteString()
}

private fun compactSignature(r: BigInteger, s: BigInteger, recoveryId: Int): ByteString = (
  r.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it } +
    s.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it } +
    byteArrayOf(recoveryId.toByte())
).toByteString()
