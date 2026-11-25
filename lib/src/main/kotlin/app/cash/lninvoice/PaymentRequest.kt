/*
 * Copyright (c) 2023 Block, Inc.
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

import app.cash.lninvoice.FieldTags.DESCRIPTION
import app.cash.lninvoice.FieldTags.DESCRIPTION_HASH
import app.cash.lninvoice.FieldTags.EXPIRY
import app.cash.lninvoice.FieldTags.EXTRA_ROUTING_INFO
import app.cash.lninvoice.FieldTags.FEATURE_BITS
import app.cash.lninvoice.FieldTags.PAYEE_NODE
import app.cash.lninvoice.FieldTags.PAYMENT_HASH
import app.cash.lninvoice.FieldTags.SECRET
import app.cash.quiver.extensions.orThrow
import app.cash.quiver.extensions.toEither
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.raise.result
import arrow.core.right
import arrow.core.some
import arrow.core.toOption
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.bitcoinj.base.Sha256Hash
import org.bitcoinj.crypto.ECKey
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant

/**
 * A request for payment, as per [BOLT-11](https://github.com/lightning/bolts/blob/master/11-payment-encoding.md#bolt-11-invoice-protocol-for-lightning-payments)
 *
 * Derived properties are decoded during construction or on first read. [parse] decodes and validates all of them,
 * including the signature and the description's BOLT1 text rules.
 */
data class PaymentRequest(
  /** In which Bitcoin network this request is valid */
  val network: Network,
  /** When the request was created */
  val timestamp: Instant,
  /** The optional amount that this request is asking for */
  val amount: Option<BitcoinAmount> = None,
  /** Hash of the request pre-image */
  val paymentHash: String,
  /** List of all the raw tagged fields in the request */
  val taggedFields: List<TaggedField> = emptyList(),
  /** Signature provided by the requesting node */
  val signature: ByteString,
  /** Checksum for this request */
  val hash: ByteString,
) {

  private val taggedFieldMap: Map<Int, TaggedField> = taggedFields.associateBy { it.tag }
  /**
   * Short description of purpose of payment (UTF-8), e.g. '1 cup of coffee' or 'ナンセンス 1杯'.
   * Invalid UTF-8 is decoded with replacement characters; [parse] rejects it.
   */
  val description: Option<String> =
    taggedFieldMap[DESCRIPTION.tag].toOption().map { BitReader(it.data).textLenient(it.size) }

  /**
   * SHA2 256-bit hash of the original description.
   * Usually set when the description is too long to store in the invoice itself.
   */
  val descriptionHash: Option<String> by lazy {
    taggedFieldMap[DESCRIPTION_HASH.tag].toOption().map { BitReader(it.data).byteString(256).hex() }
  }

  /**
   * Expiry time as defined in tagged fields. Default is 1 hour if no duration tag is present.
   * @throws ArithmeticException if the expiry overflows, which requests from [parse] never have.
   */
  val expiry: Duration by lazy {
    taggedFieldMap[EXPIRY.tag].toOption().map {
      Duration.ofSeconds(BitReader(it.data).long(it.size))
    }.getOrElse { Duration.ofHours(1) }
  }

  /**
   * Validates the signature and returns the explicit or recovered payee node public key.
   * @throws InvalidInvoice if the signature is invalid, which requests from [parse] never have.
   */
  val payeeNodePublicKey: ByteString by lazy {
    decodePayeeNodePublicKey().getOrElse { throw it as? InvalidInvoice ?: InvalidInvoice("Invalid payee node public key", it) }
  }

  /**
   * Returns [payeeNodePublicKey] when the signature is valid. Otherwise, returns the key recovered from the signature
   * without validating it and ignoring any explicit payee field, as 0.0.6 did. Use this only with [parseLenient].
   * @throws InvalidInvoice if no key can be recovered from the signature.
   */
  val payeeNodePublicKeyLenient: ByteString by lazy {
    result { payeeNodePublicKey }.getOrElse {
      recoverPayeeNodePublicKeyLenient()
        .getOrElse { throw it as? InvalidInvoice ?: InvalidInvoice("Cannot recover payee public key", it) }
    }
  }

  val routingInfo: List<RoutingInfo> =
    taggedFields.filter { it.tag == EXTRA_ROUTING_INFO.tag }.map { RoutingInfo.parse(it) }

  private fun decodePayeeNodePublicKey(): Result<ByteString> = result {
    val ellipticCurveSignature = decodeSignature().bind()
    val signatureRecoveryId = signature[64].toUByte().toInt()
    val explicitPayee = taggedFieldMap[PAYEE_NODE.tag]
    validateSignature(ellipticCurveSignature, signatureRecoveryId, requireLowS = explicitPayee != null).bind()

    val digest = Sha256Hash.wrap(hash.toByteArray())
    val payee = explicitPayee?.let {
      decodeExplicitPayee(it).bind()
    } ?: recoverPayee(signatureRecoveryId, ellipticCurveSignature, digest).bind()
    verifySignature(ellipticCurveSignature, digest, payee).bind()
    payee.pubKey.toByteString()
  }

  private fun recoverPayeeNodePublicKeyLenient(): Result<ByteString> = result {
    val ellipticCurveSignature = decodeSignature().bind()
    val digest = Sha256Hash.wrap(hash.toByteArray())
    recoverPayee(signature[64].toUByte().toInt(), ellipticCurveSignature, digest).bind().pubKey.toByteString()
  }

  private fun decodeSignature(): Result<ECKey.ECDSASignature> = result {
    ensure(signature.size == 65) { InvalidInvoice("Signature must contain 65 bytes") }
    val r = BigInteger(signature.substring(0, 32).hex(), 16)
    val s = BigInteger(signature.substring(32, 64).hex(), 16)
    ECKey.ECDSASignature(r, s)
  }

  private fun validateSignature(
    ellipticCurveSignature: ECKey.ECDSASignature,
    signatureRecoveryId: Int,
    requireLowS: Boolean,
  ): Result<Unit> = result {
    ensure(signatureRecoveryId in 0..3) { InvalidInvoice("Invalid signature recovery id") }
    val (r, s) = ellipticCurveSignature.r to ellipticCurveSignature.s
    val curveOrder = ECKey.ecDomainParameters().n
    ensure(r.signum() > 0 && r < curveOrder && s.signum() > 0 && s < curveOrder) {
      InvalidInvoice("Signature scalars are out of range")
    }
    if (requireLowS) {
      ensure(ellipticCurveSignature.isCanonical) { InvalidInvoice("Signature must use low S with an explicit payee") }
    }
  }

  private fun verifySignature(
    ellipticCurveSignature: ECKey.ECDSASignature,
    digest: Sha256Hash,
    payee: ECKey,
  ): Result<Unit> = result {
    ensure(payee.verify(digest, ellipticCurveSignature)) { InvalidInvoice("Invalid invoice signature") }
  }

  private fun decodeExplicitPayee(field: TaggedField): Result<ECKey> = result {
    ECKey.fromPublicOnly(BitReader(field.data).byteString(264).toByteArray())
  }

  private fun recoverPayee(
    signatureRecoveryId: Int,
    ellipticCurveSignature: ECKey.ECDSASignature,
    digest: Sha256Hash,
  ): Result<ECKey> = result {
    ensureNotNull(ECKey.recoverFromSignature(signatureRecoveryId, ellipticCurveSignature, digest, true)) {
      InvalidInvoice("Cannot recover payee public key")
    }
  }

  companion object {

    private const val SIGNATURE_BYTE_SIZE = 104
    private val KNOWN_INVOICE_FEATURE_BITS: Set<Int> = setOf(
      8, 9,   // var_onion_optin
      14, 15, // payment_secret
      16, 17, // basic_mpp
      24, 25, // option_route_blinding
      36, 37, // option_attribution_data
      48, 49, // option_payment_metadata
    )
    private val HrpRegex = Regex("ln([a-z]+)(([0-9]*)([munp])?)?(.*)")

    /**
     * Parse an encoded invoice into a PaymentRequest, validating all decoded properties.
     */
    fun parse(encoded: String, strict: Boolean = false): Either<InvalidInvoice, PaymentRequest> = either {
      val decoded = decodeBech32String(encoded)
        .bind()
      ensure(decoded.encoding == Bech32Data.Companion.Encoding.BECH32) {
        InvalidInvoice("Invoice must use Bech32 encoding")
      }

      val hrp = HrpRegex.matchEntire(decoded.hrp).toOption().toEither {
        InvalidInvoice("Cannot parse invoice. Bad HRP. [hrp=${decoded.hrp}]")
      }.flatMap {
        if (it.groupValues.size > 5 && it.groupValues[5].isNotEmpty()) {
          InvalidInvoice("Unexpected suffix in HRP. [hrp=${decoded.hrp}][suffix=${it.groupValues[5]}]").left()
        } else {
          it.right()
        }
      }.bind()

      val network = parseNetwork(hrp, encoded)
        .bind()

      val amount: Option<BitcoinAmount> = Either.catch {
        when (hrp.groupValues[2]) {
          "" -> None
          else -> {
            val scale = when (hrp.groupValues[4]) {
              "p" -> 12
              "n" -> 9
              "u" -> 6
              "m" -> 3
              else -> 0
            }
            BigDecimal(hrp.groupValues[3]).movePointLeft(scale).toBitcoinAmount().some()
          }
        }
      }.mapLeft { InvalidInvoice("Invalid or unrepresentable amount. [hrp=${decoded.hrp}]", it) }.flatMap {
        if (it.isSome { a -> a.picoRemainder % 10 != 0 }) {
          InvalidInvoice("Invalid amount. Pico amounts must be a multiple of 10. [hrp=${decoded.hrp}]").left()
        } else {
          it.right()
        }
      }.bind()

      val reader = Either.catch {
        BitReader(decoded.payload.substring(0, decoded.payload.size - SIGNATURE_BYTE_SIZE))
      }.mapLeft { InvalidInvoice("Invoice too short [invoice=$encoded]", it) }
        .bind()

      val timestamp: Instant = Either.catch { reader.timestamp(7) }.mapLeft {
        InvalidInvoice(
          "Cannot parse timestamp from data. " +
            "[invoice=$encoded][data=${decoded.payload.hex()}]",
          it
        )
      }.bind()

      val taggedFields = Either.catch { reader.taggedFields() }.mapLeft {
        InvalidInvoice(
          "Cannot parse tagged fields from data. " +
            "[invoice=$encoded][data=${decoded.payload.hex()}]",
          it
        )
      }.bind()

      taggedFields.forEach { field ->
        val expectedSize = when (field.tag) {
          PAYMENT_HASH.tag, DESCRIPTION_HASH.tag, SECRET.tag -> 52
          PAYEE_NODE.tag -> 53
          else -> return@forEach
        }
        ensure(field.size == expectedSize) {
          InvalidInvoice("Invalid tagged field length. [tag=${field.tag}][size=${field.size}][expected=$expectedSize]")
        }
      }

      val paymentHashField = taggedFields.find { it.tag == PAYMENT_HASH.tag }.toEither {
        InvalidInvoice("Invoice did not include a payment hash [invoice=$encoded]")
      }.bind()

      val validatedTaggedFields = validateTaggedFields(taggedFields, strict).bind()

      Either.catch {
        val sigWords = decoded.payload.substring(decoded.payload.size - SIGNATURE_BYTE_SIZE, decoded.payload.size)
        val signature = BitReader(sigWords).byteString(520)

        PaymentRequest(
          network = network,
          timestamp = timestamp,
          amount = amount,
          paymentHash = BitReader(paymentHashField.data).byteString(256).hex(),
          taggedFields = validatedTaggedFields,
          signature = signature,
          hash = hashData(decoded),
        ).also { request ->
          // The constructor decodes descriptions leniently and the other properties lazily so that parseLenient can
          // build requests. Strict decoding and reading the properties throw here instead, which the surrounding
          // Either.catch turns into InvalidInvoice.
          validatedTaggedFields.find { it.tag == DESCRIPTION.tag }?.let { BitReader(it.data).text(it.size) }
          request.descriptionHash
          request.expiry
          request.payeeNodePublicKey
        }
      }.mapLeft { InvalidInvoice("Cannot parse invoice data. [invoice=$encoded]", it) }.bind()
    }

    private fun parseNetwork(hrp: MatchResult, encoded: String): Either<InvalidInvoice, Network> =
      Network.parse(hrp.groupValues[1])
        .mapLeft { InvalidInvoice("Invalid network. [invoice=$encoded]", it) }

    private fun decodeBech32String(encoded: String): Either<InvalidInvoice, Bech32Data> =
      encoded.toBech32Data()
        .mapLeft { InvalidInvoice("Failed to bech32 decode [invoice=$encoded]", it) }

    /**
     * Parse an invoice that [parse] in version 0.0.6 accepted, such as one read back from storage.
     *
     * This does not follow BOLT-11, and it does not validate the signature. It accepts invoices that BOLT-11 requires
     * readers to reject, so use [parse] to accept new invoices and use this only for invoices stored earlier.
     *
     * This skips checks added in 0.0.7. The invoice may use Bech32m or have characters around it, repeat the payment
     * hash, lack or repeat the payment secret or description, or have tagged fields of unexpected length. A description
     * that is not valid BOLT1 text is decoded with replacement characters. As in 0.0.6, a malformed description hash
     * or an overflowing expiry only throws when [descriptionHash] or [expiry] is read. [payeeNodePublicKey] throws
     * [InvalidInvoice] when the signature is invalid; [payeeNodePublicKeyLenient] returns the key 0.0.6 recovered.
     *
     * A payment hash is still required, and the checksum, network, exact amount, timestamp, required feature bits, and
     * routing info are still validated.
     */
    fun parseLenient(encoded: String): Either<InvalidInvoice, PaymentRequest> = either {
      val decoded = decodeBech32StringLenient(encoded)
        .bind()
      val hrp = HrpRegex.find(decoded.hrp).toOption().toEither {
        InvalidInvoice("Cannot parse invoice. Bad HRP. [hrp=${decoded.hrp}]")
      }.flatMap {
        if (it.groupValues.size > 5 && it.groupValues[5].isNotEmpty()) {
          InvalidInvoice("Unexpected suffix in HRP. [hrp=${decoded.hrp}][suffix=${it.groupValues[5]}]").left()
        } else {
          it.right()
        }
      }.bind()

      val network = parseNetwork(hrp, encoded)
        .bind()

      val amount: Option<BitcoinAmount> = Either.catch {
        when (hrp.groupValues[2]) {
          "" -> None
          else -> {
            val scale = when (hrp.groupValues[4]) {
              "p" -> 12
              "n" -> 9
              "u" -> 6
              "m" -> 3
              else -> 0
            }
            BigDecimal(hrp.groupValues[3]).movePointLeft(scale).toBitcoinAmount().some()
          }
        }
      }.mapLeft { InvalidInvoice("Invalid or unrepresentable amount. [hrp=${decoded.hrp}]", it) }.flatMap {
        if (it.isSome { a -> a.picoRemainder % 10 != 0 }) {
          InvalidInvoice("Invalid amount. Pico amounts must be a multiple of 10. [hrp=${decoded.hrp}]").left()
        } else {
          it.right()
        }
      }.bind()

      val reader = Either.catch {
        BitReader(decoded.payload.substring(0, decoded.payload.size - SIGNATURE_BYTE_SIZE))
      }.mapLeft { InvalidInvoice("Invoice too short [invoice=$encoded]", it) }
        .bind()

      val timestamp: Instant = Either.catch { reader.timestamp(7) }.mapLeft {
        InvalidInvoice(
          "Cannot parse timestamp from data. " +
            "[invoice=$encoded][data=${decoded.payload.hex()}]",
          it
        )
      }.bind()

      val taggedFields = Either.catch { reader.taggedFields() }.mapLeft {
        InvalidInvoice(
          "Cannot parse tagged fields from data. " +
            "[invoice=$encoded][data=${decoded.payload.hex()}]",
          it
        )
      }.bind()

      val paymentHashField = taggedFields.find { it.tag == PAYMENT_HASH.tag }.toEither {
        InvalidInvoice("Invoice did not include a payment hash [invoice=$encoded]")
      }.bind()

      validateFeatureBits(taggedFields).bind()

      Either.catch {
        val sigWords = decoded.payload.substring(decoded.payload.size - SIGNATURE_BYTE_SIZE, decoded.payload.size)
        val signature = BitReader(sigWords).byteString(520)

        PaymentRequest(
          network = network,
          timestamp = timestamp,
          amount = amount,
          paymentHash = BitReader(paymentHashField.data).byteString(256).hex(),
          taggedFields = taggedFields,
          signature = signature,
          hash = hashData(decoded),
        )
      }.mapLeft { InvalidInvoice("Cannot parse invoice data. [invoice=$encoded]", it) }.bind()
    }

    private fun decodeBech32StringLenient(encoded: String): Either<InvalidInvoice, Bech32Data> =
      Bech32Data.decodeLenient(encoded)
        .mapLeft { InvalidInvoice("Failed to bech32 decode [invoice=$encoded]", it) }

    private fun validateTaggedFields(
      taggedFields: List<TaggedField>,
      strict: Boolean,
    ): Either<InvalidInvoice, List<TaggedField>> = either {
      ensure(taggedFields.count { it.tag == PAYMENT_HASH.tag } == 1) {
        InvalidInvoice("Invoice must include exactly one payment hash")
      }
      ensure(taggedFields.count { it.tag == SECRET.tag } == 1) {
        InvalidInvoice("Invoice must include exactly one payment secret")
      }
      ensure(taggedFields.count { it.tag == DESCRIPTION.tag || it.tag == DESCRIPTION_HASH.tag } == 1) {
        InvalidInvoice("Invoice must include exactly one description or description hash")
      }
      val unknown: Set<Int> by lazy { taggedFields.map { it.tag }.toSet().minus(FieldTags.validTags().toSet()) }
      ensure(!strict || unknown.isEmpty()) {
        InvalidInvoice("Tagged field has unknown tag(s) [${unknown.joinToString(",")}]")
      }
      validateFeatureBits(taggedFields).bind()
      taggedFields
    }

    private fun validateFeatureBits(
      taggedFields: List<TaggedField>,
    ): Either<InvalidInvoice, Unit> {
      val featureField = taggedFields.find { it.tag == FEATURE_BITS.tag } ?: return Unit.right()
      val setBits = BitReader(featureField.data).bits(featureField.size)
      val unknownRequired = setBits.filter { it % 2 == 0 && it !in KNOWN_INVOICE_FEATURE_BITS }
      return if (unknownRequired.isNotEmpty()) {
        InvalidInvoice(
          "Invoice requires unknown feature(s) [${unknownRequired.sorted().joinToString(",")}]"
        ).left()
      } else {
        Unit.right()
      }
    }

    private fun hashData(decoded: Bech32Data): ByteString {
      val hrp = decoded.hrp.encodeUtf8()
      val words = decoded.payload.toByteArray().dropLast(SIGNATURE_BYTE_SIZE).toByteArray().toByteString()
      val dataWithoutSig = BitReader(words).byteString(words.size * 5).toByteArray()
      val buffer = ByteBuffer.allocate(hrp.size + dataWithoutSig.size)
      buffer.put(hrp.toByteArray())
      buffer.put(dataWithoutSig)
      buffer.flip()
      return buffer.toByteString().sha256()
    }

    /**
     * Parse an encoded invoice into a PaymentRequest.
     * @throws `InvalidInvoice` if it cannot be parsed.
     */
    fun parseUnsafe(encoded: String): PaymentRequest = parse(encoded).orThrow()
  }
}

data class InvalidInvoice(
  override val message: String,
  override val cause: Throwable? = null
) : Throwable(message, cause)
