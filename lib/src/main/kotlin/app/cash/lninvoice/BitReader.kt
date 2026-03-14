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

import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.time.Instant

class BitReader(
  private val data: ByteString
) {

  private var byteIndex: Int = 0

  /** Read a timestamp by parsing `bytes` bytes into a long and considering it to be seconds since epoch */
  fun timestamp(bytes: Int): Instant = Instant.ofEpochSecond(long(bytes))

  /** Read a list of tagged fields until all data is consumed */
  fun taggedFields(): List<TaggedField> {
    tailrec fun keepOnGoing(acc: List<TaggedField>): List<TaggedField> =
      if (byteIndex >= data.size) acc else keepOnGoing(acc.plus(taggedField()))
    return keepOnGoing(emptyList())
  }

  /** Read the next tagged field */
  private fun taggedField(): TaggedField {
    val tag = int(1)
    val size = int(2)
    val bytes = bytes(size)
    return TaggedField(tag, size, bytes)
  }

  /** Read an integer by parsing `bytes` bytes, taking the lower 5 bits from each and concatenating them */
  internal fun int(bytes: Int): Int =
    bytes(bytes).toByteArray().fold(0) { acc, b ->
      (acc shl 5) + b
    }

  /** Read a long by parsing `bytes` bytes, taking the lower 5 bits from each and concatenating them */
  internal fun long(bytes: Int): Long =
    bytes(bytes).toByteArray().fold(0L) { acc, b ->
      (acc shl 5) + b
    }

  /** Capture the raw bytes */
  private fun bytes(bytes: Int): ByteString {
    val ans = data.substring(byteIndex, byteIndex + bytes)
    byteIndex += bytes
    return ans
  }

  /** Read the lower 5 bits from each byte until `bits` bits have been read, concatenate & return the resulting bytes */
  internal fun byteString(bits: Int): ByteString {
    tailrec fun bitList(remaining: Int, acc: String = ""): String =
      if (remaining <= 0) {
        // Pad the byte boundary
        val rem = acc.length % 8
        if (rem == 0) acc else acc + "0".repeat(8 - rem)
      } else {
        // Set the upper bits to zero
        val nextByte = data[byteIndex++].toInt() and FIVE_BIT_MASK

        val bitsToRead = remaining.coerceAtMost(5)
        val bitString = Integer.toBinaryString(nextByte).padStart(5, '0').take(bitsToRead)

        bitList((remaining - bitsToRead).coerceAtLeast(0), acc + bitString)
      }

    return bitList(bits).chunked(8).map { chunk -> Integer.parseInt(chunk, 2).toByte() }.toByteArray().toByteString()
  }

  /** Read `bytes` bytes (concatenating the lower 5 bits of each, as per `byteString`) and convert into UTF_8 text */
  internal fun text(bytes: Int): String {
    val bits = bytes * 5
    return byteString(bits - bits % 8).string(Charsets.UTF_8)
  }

  /**
   * Reads [wordCount] 5-bit words, combines them into a single number (MSB first),
   * and returns the set of bit positions that are set.
   *
   * For example, words [16, 8, 0] combine to binary 10000_01000_00000,
   * which has bits 8 and 14 set (var_onion_optin + payment_secret).
   */
  internal fun bits(wordCount: Int): Set<Int> {
    val value = bytes(wordCount).toByteArray().fold(BigInteger.ZERO) { acc, word ->
      acc.shiftLeft(5).add(BigInteger.valueOf(word.toLong() and 0x1FL))
    }
    return (0..value.bitLength()).filter { value.testBit(it) }.toSet()
  }

  companion object {
    const val FIVE_BIT_MASK = 0x1F
  }
}
