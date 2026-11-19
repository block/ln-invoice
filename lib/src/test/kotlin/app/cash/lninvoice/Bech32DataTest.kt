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

import app.cash.lninvoice.Bech32Data.Companion.Encoding
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.collection
import java.util.Locale

class Bech32DataTest : StringSpec({

  "BIP-173 valid bech32 can serde in either case" {
    checkAll(
      Exhaustive.collection(
        listOf(
          "A12UEL5L",
          "a12uel5l",
          "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
          "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
          "11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j",
          "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
          "?1ezyfcl"
        ).flatMap { listOf(it, it.uppercase(Locale.ROOT)) }
      )
    ) { encoded ->
      encoded.toBech32Data().shouldBeRight() should { data ->
        data.encoding shouldBe Encoding.BECH32
        data.encoded shouldBe encoded.lowercase(Locale.ROOT)
      }
    }
  }

  "BIP-350 valid bech32m can serde in either case" {
    checkAll(
      Exhaustive.collection(
        listOf(
          "A1LQFN3A",
          "a1lqfn3a",
          "an83characterlonghumanreadablepartthatcontainsthetheexcludedcharactersbioandnumber11sg7hg6",
          "abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx",
          "11llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllludsr8",
          "split1checkupstagehandshakeupstreamerranterredcaperredlc445v",
          "?1v759aa"
        ).flatMap { listOf(it, it.uppercase(Locale.ROOT)) }
      )
    ) { encoded ->
      encoded.toBech32Data().shouldBeRight() should { data ->
        data.encoding shouldBe Encoding.BECH32M
        data.encoded shouldBe encoded.lowercase(Locale.ROOT)
      }
    }
  }

  "prefix and suffix junk cannot parse" {
    checkAll(
      Exhaustive.collection(listOf("a12uel5l", "A12UEL5L", "a1lqfn3a", "A1LQFN3A")),
      Exhaustive.collection(listOf("!", " ", "\t", "\n", "\r\n", "\u0000", "\u007f", "\u0080"))
    ) { encoded, junk ->
      "$junk$encoded".toBech32Data().shouldBeLeft()
      "$encoded$junk".toBech32Data().shouldBeLeft()
    }
  }

  "mixed case cannot parse" {
    checkAll(
      Exhaustive.collection(
        listOf(
          "a12UEL5L", "A12uel5l", "a12uel5L", "a12uel5lA", "aA12UEL5L",
          "a1LQFN3A", "A1lqfn3a", "a1lqfn3A", "a1lqfn3aA", "aA1LQFN3A"
        )
      )
    ) { encoded ->
      encoded.toBech32Data().shouldBeLeft()
    }
  }

  "changed checksums are rejected" {
    checkAll(Exhaustive.collection(listOf("a12uel5q", "a1lqfn3q"))) { encoded ->
      encoded.toBech32Data().shouldBeLeft().shouldBeInstanceOf<InvalidChecksum>()
    }
  }

  "Lightning invoice payloads longer than 90 characters can serde in either case" {
    checkAll(Exhaustive.collection(listOf(Invoices.sample, Invoices.sample.uppercase(Locale.ROOT)))) { encoded ->
      encoded.toBech32Data().shouldBeRight() should { data ->
        data.encoding shouldBe Encoding.BECH32
        data.encoded shouldBe Invoices.sample
      }
    }
  }

  "BIP-173 and BIP-350 invalid bech32(m) cannot parse" {
    checkAll(
      Exhaustive.collection(
        listOf(
          " 1nwldj5", // HRP character out of range
          "\u007f1axkwrx", // HRP character out of range
          "\u00801eym55h", // HRP character out of range
          "an84characterslonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1569pvx",
          "pzry9x0s0muk", // No separator character
          "1pzry9x0s0muk", // Empty HRP
          "x1b4n0q5v", // Invalid data character
          "li1dgmt3", // Too short checksum
          "de1lg7wt\u00ff", // Invalid character in checksum
          "A1G7SGD8", // checksum calculated with uppercase form of HRP
          "10a06t8", // empty HRP
          "1qzzfhee", // empty HRP
          " 1xj0phk", // HRP character out of range
          "\u007f1g6xzxy", // HRP character out of range
          "\u00801vctc34", // HRP character out of range
          "an84characterslonghumanreadablepartthatcontainsthetheexcludedcharactersbioandnumber11d6pts4",
          "qyrz8wqd2c9m", // No separator character
          "1qyrz8wqd2c9m", // Empty HRP
          "y1b0jsk6g", // Invalid data character
          "lt1igcx5c0", // Invalid data character
          "in1muywd", // Too short checksum
          "mm1crxm3i", // Invalid character in checksum
          "au1s5cgom", // Invalid character in checksum
          "M1VUXWEZ", // checksum calculated with uppercase form of HRP
          "16plkw9", // empty HRP
          "1p2gdwpf" // empty HRP
        )
      )
    ) { encoded ->
      encoded.toBech32Data().shouldBeLeft()
    }
  }
})
