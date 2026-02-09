/*
 * Copyright (c) 2025 Block, Inc.
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

import okio.Buffer
import okio.ByteString

data class RoutingInfo(val hops: List<HopHint>) {

  companion object {
    private const val PUBKEY_BYTES = 33
    private const val SHORT_CHANNEL_ID_BYTES = 8
    private const val FEE_BASE_MSAT_BYTES = 4
    private const val FEE_PROPORTIONAL_MILLIONTHS_BYTES = 4
    private const val CLTV_EXPIRY_DELTA_BYTES = 2
    private const val HOP_HINT_BYTES =
      PUBKEY_BYTES + SHORT_CHANNEL_ID_BYTES + FEE_BASE_MSAT_BYTES + FEE_PROPORTIONAL_MILLIONTHS_BYTES + CLTV_EXPIRY_DELTA_BYTES

    fun parse(taggedField: TaggedField): RoutingInfo {
      val totalBits = taggedField.size * 5
      val rawBytes = BitReader(taggedField.data).byteString(totalBits)
      val buffer = Buffer().write(rawBytes)

      val hops = (0 until rawBytes.size / HOP_HINT_BYTES).map {
        HopHint(
          pubkey = buffer.readByteString(PUBKEY_BYTES.toLong()),
          shortChannelId = ShortChannelId.parse(buffer.readByteString(SHORT_CHANNEL_ID_BYTES.toLong())),
          feeBaseMsat = buffer.readInt(),
          feeProportionalMillionths = buffer.readInt(),
          cltvExpiryDelta = buffer.readShort(),
        )
      }
      return RoutingInfo(hops)
    }
  }
}

data class HopHint(
  val pubkey: ByteString,
  val shortChannelId: ShortChannelId,
  val feeBaseMsat: Int,
  val feeProportionalMillionths: Int,
  val cltvExpiryDelta: Short,
)

data class ShortChannelId(
  val blockHeight: Int,
  val txIndex: Int,
  val outputIndex: Int,
) {
  override fun toString(): String = "${blockHeight}x${txIndex}x${outputIndex}"

  companion object {
    fun parse(bytes: ByteString): ShortChannelId {
      require(bytes.size == 8) { "short_channel_id must be 8 bytes" }
      val buffer = Buffer().write(bytes)
      return ShortChannelId(
        blockHeight = buffer.readUInt24(),
        txIndex = buffer.readUInt24(),
        outputIndex = buffer.readShort().toUShort().toInt(),
      )
    }

    // Reads 3 bytes and combines them into a 24-bit big-endian unsigned integer.
    // `shl` shifts each byte into its position, `or` merges them:
    //   byte1: 0x01 shl 16 -> 0x010000 (bits 23-16)
    //   byte2: 0x02 shl  8 -> 0x000200 (bits 15-8)
    //   byte3: 0x03        -> 0x000003 (bits 7-0)
    //                    or = 0x010203
    private fun Buffer.readUInt24(): Int =
      (readByte().toUByte().toInt() shl 16) or
        (readByte().toUByte().toInt() shl 8) or
        readByte().toUByte().toInt()
  }
}
