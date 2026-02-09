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

import app.cash.quiver.extensions.orThrow

object Invoices {

  const val sample = "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq" +
    "5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqq" +
    "qqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359" +
    "k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu"

  const val sampleWithPaymentHash =
    "lnbc9678785340p1pwmna7lpp5gc3xfm08u9qy06djf8dfflhugl6p7lgza6dsjxq454gx" +
      "hj9t7a0sd8dgfkx7cmtwd68yetpd5s9xar0wfjn5gpc8qhrsdfq24f5ggrxdaezqsnvda3kkum5wfjkzmfqf3j" +
      "kgem9wgsyuctwdus9xgrcyqcjcgpzgfskx6eqf9hzqnteypzxz7fzypfhg6trddjhygrcyqezcgpzfysywmm5y" +
      "pxxjemgw3hxjmn8yptk7untd9hxwg3q2d6xjcmtv4ezq7pqxgsxzmnyyqcjqmt0wfjjq6t5v4khxsp5zyg3zyg" +
      "3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygsxqyjw5qcqp2rzjq0gxwkzc8w6323m55m4jyxcjwmy" +
      "7stt9hwkwe2qxmy8zpsgg7jcuwz87fcqqeuqqqyqqqqlgqqqqn3qq9q9qrsgqrvgkpnmps664wgkp43l22qsgd" +
      "w4ve24aca4nymnxddlnp8vh9v2sdxlu5ywdxefsfvm0fq3sesf08uf6q9a2ke0hc9j6z6wlxg5z5kqpu2v9wz"

  const val pubkeyRecoveryTest =
    "lnbc69420n1psmnpx3pp5uaycaj6z4xta6f9235py6xzxekanr593hhycm4xz5uvfmdenps4qdpqwp6ky6m90ys" +
      "8yetrdamx2uneyp6x2um5cqzpgxqrrsssp5feksfv22atsf9ej9z6h6q4hg6uk7zyt454g7hhjt5ej8t5kw" +
      "qv6s9qyyssqy38g7quvc37egftsnw9urvztt7p5xsajhdrvxyseytv5feyw7fnkj599fr9k0tw3atql5p4g" +
      "9esuv69qakj3q85r7mwx4crsuwjmahgpxqnhrw"

  const val signatureOverflowTest = "lnbc1u1psa8wkepp5ly0f09hm3757w7pww6s78nykge4qea4kjas6vj" +
    "tfjuaaycucpyzqdqqcqzpgxqyz5vqsp5uxllhnws7kfr85exa7phjaadmgmtau8pqeffggqm6t46jxma987q9" +
    "qyyssqhhfttgqemkd2q5xhgwd3gdcqzqsmh5mlclkl3gjfy7lgygf7f5746cfwplpvhpzucpeg4ptzfk9k94c" +
    "6w8f4dagjkjhz03lklat542cpvm5ps5"

  const val sampleWithDescriptionHash = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg" +
    "3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmda" +
    "n79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs9qrsgq7ea976txfraylvgzuxs8kgcw23ezlrszf" +
    "nh8r6qtfpr6cxga50aj6txm9rxrydzd06dfeawfk6swupvz4erwnyutnjq7x39ymw6j38gp7ynn44"

  const val sampleWithDescriptionAndDescriptionHash = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfq" +
    "qqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg" +
    "3zygsdq823jhxaqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs9qrsgqxqrrsscqp" +
    "fr6twf5rlcx42v9x9pq3upsl0m24z2gtkwmkzcwehx2rcqrdgs5540lz8m0lhs3z7t8luva6c5hlg6l5jsw4x" +
    "3lnwuauqvxzmmcqen3gpj7tnq2"

  const val sampleWithUnknownTags = "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfq" +
    "qqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3" +
    "zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2qrqqqfppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqppnqqqqq" +
    "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
    "qqqqqqqqqqqqqqqqqhpnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhp4qqqqqqqqqqq" +
    "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqspnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
    "qqqqqqqqqqqsp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnp5qqqqqqqqqqqqqqq" +
    "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnpkqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" +
    "qqqqqqqqqz599y53s3ujmcfjp5xrdap68qxymkqphwsexhmhr8wdz5usdzkzrse33chw6dlp3jhuhge9ley7j" +
    "2ayx36kawe7kmgg8sv5ugdyusdcqzn8z9x"

  const val sampleWithUnevenBytes = "lnbc820n1p58jmqqdq4ga6kccmgw3jkx6pqf3xyxpp5gz4f05gw6k5sxypgqr" +
    "ap9sp240lwtxe9tqzull4k9mwxuq2n2r5ssp598qjc2nx7xtjx6csk5j7cgh25q2ahwpurcv859dhwj8n2xw4" +
    "3lds9qrsgqcqpcxqzfvrzjqg4aqpxjt4y6x7ncy45rcwue0r8zp90sflh6ufuvr4844aa36x4vgzqdryqqzqq" +
    "qqsqqqqqqqqqqqqqq9grzjqdt5stcc3ms9jelz5eqg0mrzz45ckf30f2whjt6g2yqwhkdc9yy82z2lw5qqzxs" +
    "qq5qqqqqqqqqqqqqq9gxhvk5ns8sdtjhp097v5mlv8dl7zuxuale70lct7r0vf6hkggf7pkps6qwgnuxatt5r" +
    "tuzu8vjp3u3q02vuejj5ynalhdt2ydclc2rngpqtm9kn"


  const val sampleSignet = "lntbs20020n1p5kdssjpp5tj0x70z6j8ajw7jh2hsd06hjwlwt5a53rgmc48ksda6ea2ur" +
    "cpuqdqqcqzzsxqyz5vqsp55w9nlefu2zz2hp65vpvqexxuf9knqu4ea6k5c3kt5jufuzkwyuxs9qxpqysgq42g" +
    "d376f5lpcf4vrq050fmuptnvczzaudds0ptcj8v2gq3skxvlz8kn0h8m9vf8j3yzgqnlq2pqgds3huxts8s2hc" +
    "r7seyuwj9g0t0cpg3mn4f"

  const val sampleWithRoutingInfo = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg" +
    "3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4z" +
    "wqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jt" +
    "zrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5" +
    "gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzq9qrsgqdfjcdk6w3ak5pca9hwfw" +
    "fh63zrrz06wwfya0ydlzpgzxkn5xagsqz7x9j4jwe7yj7vaf2k9lqsdk45kts2fd0fkr28am0u4w95tt2nsq76" +
    "cqw0"


  val sampleDecoded: Bech32Data by lazy { sample.toBech32Data().orThrow() }
}
