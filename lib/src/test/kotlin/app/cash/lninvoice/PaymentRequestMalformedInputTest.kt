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
    val descriptionHash = field(FieldTags.DESCRIPTION_HASH, 52)
    listOf(FieldTags.PAYMENT_HASH, FieldTags.DESCRIPTION_HASH, FieldTags.SECRET, FieldTags.PAYEE_NODE).forEach { tag ->
      val invalidSizes = if (tag == FieldTags.PAYEE_NODE) listOf(0, 1, 52, 54) else listOf(0, 1, 51, 53)
      invalidSizes.forEach { size ->
        val malformed = field(tag, size)
        val validFields = sampleFields + descriptionHash
        listOf(listOf(malformed) + validFields, validFields + malformed).forEach { fields ->
          PaymentRequest.parse(invoice(fields)).shouldBeLeft()
        }
      }
    }
  }

  "invalid UTF-8 descriptions fail during safe parsing" {
    val badUtf8 = TaggedField(FieldTags.DESCRIPTION.tag, 2, byteArrayOf(31, 28).toByteString())
    PaymentRequest.parse(invoice(sampleFields + badUtf8)).shouldBeLeft()
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

  "invalid signatures report InvalidInvoice at the throwing getter boundary" {
    val request = PaymentRequest.parse(Invoices.sample).shouldBeRight()
    val signature = compactSignature(BigInteger.ZERO, BigInteger.ONE, 0)
    shouldThrow<InvalidInvoice> { request.copy(signature = signature).payeeNodePublicKey }
    shouldThrow<InvalidInvoice> { request.copy(signature = ByteString.EMPTY).payeeNodePublicKey }
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
