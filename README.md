![LN Invoice Logo](./images/ln-invoice-transparent.png)

Parse Bitcoin Lightning Network invoices (payment requests) in Kotlin. This library implements decoding of invoices as per
[BOLT-11: # Invoice Protocol for Lightning Payments](https://github.com/lightning/bolts/blob/master/11-payment-encoding.md#bolt-11-invoice-protocol-for-lightning-payments)

[<img src="https://img.shields.io/maven-central/v/app.cash.lninvoice/ln-invoice.svg?label=latest%20release"/>](https://central.sonatype.com/namespace/app.cash.lninvoice)

## Getting Started

On the [Sonatype page for ln-invoice](https://central.sonatype.com/namespace/app.cash.lninvoice), choose the latest version
of `ln-invoice` and follow the instructions for inclusion in your build tool.

### Parsing a payment request

```kotlin
const val sample = "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq" +
  "5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqq" +
  "qqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359" +
  "k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu"

val request: ErrorOr<PaymentRequest> = PaymentRequest.parse(sample)
```

See `app.cash.lninvoice.PaymentRequestTest` for more parsing examples, lifted directly from the BOLT-11 spec.

LN-Invoice is functional programming safe by default, using [Quiver](https://github.com/block/quiver)
(an extension of [Arrow](https://arrow-kt.io/)). If you prefer to deal with thrown exceptions when parsing,
simply add `.orThrow()`.

```kotlin
val request: PaymentRequest = PaymentRequest.parse(sample).orThrow()
```

### Lenient parsing of stored invoices

`PaymentRequest.parse` follows BOLT-11. `PaymentRequest.parseLenient` does not: it accepts invoices that version 0.0.6
accepted but that later versions reject, so that invoices already stored before an upgrade can still be read back.
Use `parse` for every invoice you have not yet accepted, and `parseLenient` only for invoices you stored earlier.

`parseLenient` does not validate the signature. Reading `payeeNodePublicKey` checks it and throws `InvalidInvoice` when
it is invalid. `payeeNodePublicKeyLenient` instead falls back to the key recovered from the signature, ignoring any
explicit payee field, as 0.0.6 did. It returns a key that may not have signed the invoice, so use it only for invoices
read with `parseLenient`.

`parseLenient` also accepts invoices that BOLT-11 requires readers to reject: ones without a payment secret, without a
description or description hash, or with repeated or wrongly sized fields. It accepts Bech32m, characters around the
invoice, and descriptions that are not valid BOLT-1 text. As in 0.0.6, a malformed description hash or an overflowing
expiry throws only when `descriptionHash` or `expiry` is read. It still rejects a bad checksum, an unknown network, a
missing payment hash, an invalid amount, and unknown required feature bits.

```kotlin
val stored: ErrorOr<PaymentRequest> = PaymentRequest.parse(encoded).recover { PaymentRequest.parseLenient(encoded).bind() }
```

Fields in `PaymentRequest` instances that contain binary data use 
[Okio's ByteString](https://square.github.io/okio/).
If you need this as a byte array, call `.toByteArray()`

```kotlin
val signature: ByteString = invoice.signature
val sigBytes: ByteArray = signature.toByteArray()
```


## Documentation

The [API documentation](https://block.github.io/ln-invoice) is published with each release.

## Changelog

See a list of changes in each release in the [CHANGELOG](CHANGELOG.md).

## Contributing

### Building

1. Install [Hermit](https://cashapp.github.io/hermit/), the hermetic project tooling tool.

2. Use `gradle` to run all tests locally:

```shell
gradle build
```

For more details on contributing, see the [CONTRIBUTING](CONTRIBUTING.md) guide.
